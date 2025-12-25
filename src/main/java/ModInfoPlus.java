import arc.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.graphics.*;
import arc.func.*;
import arc.net.*;
import arc.util.Http.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

public class ModInfoPlus extends Mod {
    
    // Constants
    private static final int COOLDOWN_SECONDS = 60;
    private static final int CACHE_TIME_MS = 300000;
    private static final int DISCOVER_CACHE_TIME_MS = 900000;
    private static final int MODS_PER_PAGE = 3;
    private static final int MAX_NOTIFICATIONS = 50;
    
    // Storage keys
    private static final String WATCHLIST_KEY = "modinfo_watchlist_v2";
    private static final String NOTIFICATIONS_KEY = "modinfo_notifications_v3";
    private static final String DISCOVER_CACHE_KEY = "modinfo_discover_cache_v1";
    
    // State
    private ObjectMap<String, ModStats> statsCache = new ObjectMap<>();
    private ObjectMap<String, String> readmeCache = new ObjectMap<>();
    private Seq<ModInfo> discoverCache = new Seq<>();
    private Seq<ModInfo> watchlist = new Seq<>();
    private Seq<Notification> notifications = new Seq<>();
    private long lastRefreshTime = 0;
    private long lastDiscoverFetchTime = 0;
    private boolean isLoadingMods = false;
    
    private String searchQuery = "";
    private String currentCategory = "Mods";
    private int currentPage = 0;
    
    public ModInfoPlus() {
        Log.info("ModInfo+ v1.5 loaded");
    }
    
    @Override
    public void init() {
        Events.on(ClientLoadEvent.class, e -> {
            loadWatchlist();
            loadNotifications();
            loadDiscoverCache();
            
            Vars.ui.menuGroup.addChild(Vars.ui.menuGroup.fill(t -> {
                t.bottom();
                t.button("ModInfo+", Icon.info, () -> showStatsDialog()).size(210f, 60f);
            }));
        });
    }
    
    // ========== DATA CLASSES ==========
    
    public static class ModInfo {
        public String owner = "";
        public String repo = "";
        public String name = "";
        public String description = "";
        public int stars = 0;
        public String url = "";
        public long addedTime = 0;
        
        public ModInfo() {}
        
        public ModInfo(String owner, String repo, String name) {
            this.owner = owner;
            this.repo = repo;
            this.name = name;
        }
        
        public String getKey() {
            return owner + "/" + repo;
        }
    }
    
    public static class ModStats {
        public int downloads = 0;
        public int releases = 0;
        public int stars = 0;
        public String latestRelease = null;
        public String firstRelease = null;
        public int growthRate = 0;
        public long cacheTime = 0;
        public boolean error = false;
        
        public ModStats() {}
    }
    
    public static class Notification {
        public String owner = "";
        public String repo = "";
        public String modName = "";
        public String type = "";
        public String message = "";
        public long time = 0;
        public boolean read = false;
        
        public Notification() {}
    }
    
    // ========== STORAGE METHODS ==========
    
    private void loadWatchlist() {
        try {
            String json = Core.settings.getString(WATCHLIST_KEY, "[]");
            Seq<ModInfo> loaded = new Seq<>();
            
            JsonValue val = new JsonReader().parse(json);
            if (val != null && val.isArray()) {
                for (JsonValue item : val) {
                    ModInfo mod = new ModInfo();
                    mod.owner = item.getString("owner", "");
                    mod.repo = item.getString("repo", "");
                    mod.name = item.getString("name", "");
                    mod.description = item.getString("description", "");
                    mod.stars = item.getInt("stars", 0);
                    mod.url = item.getString("url", "");
                    mod.addedTime = item.getLong("addedTime", 0);
                    loaded.add(mod);
                }
            }
            
            watchlist = loaded;
        } catch (Exception e) {
            Log.err("Failed to load watchlist", e);
            watchlist = new Seq<>();
        }
    }
    
    private void saveWatchlist() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < watchlist.size; i++) {
                if (i > 0) sb.append(",");
                ModInfo mod = watchlist.get(i);
                sb.append("{");
                sb.append("\"owner\":\"").append(mod.owner).append("\",");
                sb.append("\"repo\":\"").append(mod.repo).append("\",");
                sb.append("\"name\":\"").append(mod.name).append("\",");
                sb.append("\"description\":\"").append(mod.description.replace("\"", "\\\"")).append("\",");
                sb.append("\"stars\":").append(mod.stars).append(",");
                sb.append("\"url\":\"").append(mod.url).append("\",");
                sb.append("\"addedTime\":").append(mod.addedTime);
                sb.append("}");
            }
            sb.append("]");
            
            Core.settings.put(WATCHLIST_KEY, sb.toString());
        } catch (Exception e) {
            Log.err("Failed to save watchlist", e);
        }
    }
    
    private void loadNotifications() {
        try {
            String json = Core.settings.getString(NOTIFICATIONS_KEY, "[]");
            notifications = new Seq<>();
        } catch (Exception e) {
            Log.err("Failed to load notifications", e);
            notifications = new Seq<>();
        }
    }
    
    private void saveNotifications() {
        try {
            Core.settings.put(NOTIFICATIONS_KEY, "[]");
        } catch (Exception e) {
            Log.err("Failed to save notifications", e);
        }
    }
    
    private void loadDiscoverCache() {
        try {
            String json = Core.settings.getString(DISCOVER_CACHE_KEY, "[]");
            discoverCache = new Seq<>();
            lastDiscoverFetchTime = Core.settings.getLong("modinfo_discover_time_v1", 0L);
        } catch (Exception e) {
            Log.err("Failed to load discover cache", e);
            discoverCache = new Seq<>();
        }
    }
    
    private void saveDiscoverCache() {
        try {
            Core.settings.put(DISCOVER_CACHE_KEY, "[]");
            Core.settings.put("modinfo_discover_time_v1", lastDiscoverFetchTime);
        } catch (Exception e) {
            Log.err("Failed to save discover cache", e);
        }
  }// ========== GITHUB API METHODS (v154.3 compatible) ==========
    
    private void fetchDiscoverMods(Cons<Seq<ModInfo>> callback) {
        long now = Time.millis();
        
        if (discoverCache.size > 0 && (now - lastDiscoverFetchTime) < DISCOVER_CACHE_TIME_MS) {
            callback.get(discoverCache);
            return;
        }
        
        if (isLoadingMods) {
            callback.get(discoverCache);
            return;
        }
        
        isLoadingMods = true;
        
        String url = "https://api.github.com/search/repositories?q=topic:mindustry-mod+fork:false+stars:>=1&sort=updated&order=desc&per_page=100";
        
        HttpRequest req = new HttpRequest();
        req.method = HttpMethod.GET;
        req.url = url;
        req.timeout = 10000;
        
        Core.net.httpRequest(req, result -> {
            try {
                JsonValue json = new JsonReader().parse(result.getResultAsString());
                JsonValue items = json.get("items");
                
                Seq<ModInfo> mods = new Seq<>();
                
                if (items != null && items.isArray()) {
                    for (JsonValue item : items) {
                        JsonValue topics = item.get("topics");
                        boolean hasModTopic = false;
                        
                        if (topics != null && topics.isArray()) {
                            for (JsonValue topic : topics) {
                                if ("mindustry-mod".equals(topic.asString())) {
                                    hasModTopic = true;
                                    break;
                                }
                            }
                        }
                        
                        int stars = item.getInt("stargazers_count", 0);
                        
                        if (hasModTopic && stars >= 1) {
                            ModInfo mod = new ModInfo();
                            JsonValue owner = item.get("owner");
                            if (owner != null) {
                                mod.owner = owner.getString("login", "");
                            }
                            mod.repo = item.getString("name", "");
                            mod.name = item.getString("name", "");
                            mod.description = item.getString("description", "No description");
                            mod.stars = stars;
                            mod.url = item.getString("html_url", "");
                            
                            mods.add(mod);
                        }
                    }
                }
                
                discoverCache = mods;
                lastDiscoverFetchTime = Time.millis();
                saveDiscoverCache();
                isLoadingMods = false;
                
                Core.app.post(() -> callback.get(mods));
                
            } catch (Exception e) {
                Log.err("Failed to parse GitHub response", e);
                isLoadingMods = false;
                Core.app.post(() -> callback.get(discoverCache));
            }
            
        }, error -> {
            Log.err("Failed to fetch from GitHub: @", error);
            isLoadingMods = false;
            Core.app.post(() -> callback.get(discoverCache));
        });
    }
    
    private void fetchModStats(ModInfo mod, Cons<ModStats> callback) {
        String cacheKey = mod.getKey();
        long now = Time.millis();
        
        if (statsCache.containsKey(cacheKey)) {
            ModStats cached = statsCache.get(cacheKey);
            if (now - cached.cacheTime < CACHE_TIME_MS) {
                callback.get(cached);
                return;
            }
        }
        
        String url = "https://api.github.com/repos/" + mod.owner + "/" + mod.repo + "/releases";
        
        HttpRequest req = new HttpRequest();
        req.method = HttpMethod.GET;
        req.url = url;
        req.timeout = 10000;
        
        Core.net.httpRequest(req, result -> {
            try {
                JsonValue releases = new JsonReader().parse(result.getResultAsString());
                
                ModStats stats = new ModStats();
                stats.downloads = 0;
                stats.releases = 0;
                
                if (releases != null && releases.isArray()) {
                    stats.releases = releases.size;
                    
                    for (JsonValue release : releases) {
                        if (stats.latestRelease == null) {
                            stats.latestRelease = release.getString("published_at", null);
                        }
                        
                        JsonValue assets = release.get("assets");
                        if (assets != null && assets.isArray()) {
                            for (JsonValue asset : assets) {
                                stats.downloads += asset.getInt("download_count", 0);
                            }
                        }
                    }
                }
                
                stats.cacheTime = Time.millis();
                statsCache.put(cacheKey, stats);
                
                Core.app.post(() -> callback.get(stats));
                
            } catch (Exception e) {
                Log.err("Failed to parse releases: @", e);
                ModStats errorStats = new ModStats();
                errorStats.error = true;
                errorStats.downloads = -1;
                Core.app.post(() -> callback.get(errorStats));
            }
            
        }, error -> {
            Log.err("Failed to fetch releases: @", error);
            ModStats errorStats = new ModStats();
            errorStats.error = true;
            errorStats.downloads = -1;
            Core.app.post(() -> callback.get(errorStats));
        });
    }
    
    // ========== HELPER METHODS ==========
    
    private String formatNumber(int num) {
        if (num < 1000) return String.valueOf(num);
        if (num < 1000000) return String.format("%.1fK", num / 1000.0);
        return String.format("%.1fM", num / 1000000.0);
    }
    
    private String formatDate(String dateString) {
        if (dateString == null) return "N/A";
        try {
            return dateString.substring(0, 10);
        } catch (Exception e) {
            return "N/A";
        }
    }
    
    private boolean isInWatchlist(ModInfo mod) {
        for (ModInfo m : watchlist) {
            if (m.getKey().equals(mod.getKey())) {
                return true;
            }
        }
        return false;
    }
    
    private void toggleWatchlist(ModInfo mod) {
        boolean found = false;
        for (int i = 0; i < watchlist.size; i++) {
            if (watchlist.get(i).getKey().equals(mod.getKey())) {
                watchlist.remove(i);
                found = true;
                Vars.ui.showInfoToast("Removed from watchlist", 1.5f);
                break;
            }
        }
        
        if (!found) {
            mod.addedTime = Time.millis();
            watchlist.add(mod);
            Vars.ui.showInfoToast("Added to watchlist", 2f);
        }
        
        saveWatchlist();
                                           }// ========== UI METHODS ==========
    
    private void showStatsDialog() {
        BaseDialog dialog = new BaseDialog("ModInfo+ v1.5");
        dialog.cont.clear();
        
        // Top bar
        Table topBar = new Table();
        
        int unreadCount = 0;
        for (Notification n : notifications) {
            if (!n.read) unreadCount++;
        }
        
        String notifText = unreadCount > 0 ? "Inbox (" + unreadCount + ")" : "Inbox";
        
        topBar.button(notifText, Icon.info, () -> showNotificationsDialog()).size(140f, 50f);
        topBar.button("Tutorial", Icon.book, () -> showTutorialDialog()).size(140f, 50f);
        
        dialog.cont.add(topBar).row();
        dialog.cont.row();
        
        dialog.cont.add("[yellow]Loading mods from GitHub...").row();
        
        dialog.buttons.button("Close", dialog::hide).size(120f, 55f);
        dialog.buttons.button("Refresh", () -> {
            long currentTime = Time.millis();
            long timeSince = (currentTime - lastRefreshTime) / 1000;
            
            if (timeSince < COOLDOWN_SECONDS) {
                Vars.ui.showInfoToast("Wait " + (COOLDOWN_SECONDS - timeSince) + "s", 2f);
                return;
            }
            
            lastRefreshTime = currentTime;
            statsCache.clear();
            readmeCache.clear();
            discoverCache.clear();
            lastDiscoverFetchTime = 0;
            dialog.hide();
            Timer.schedule(() -> showStatsDialog(), 0.1f);
        }).size(120f, 55f);
        
        dialog.show();
        
        fetchDiscoverMods(mods -> {
            dialog.cont.clear();
            dialog.cont.add(topBar).row();
            dialog.cont.row();
            
            if (mods.isEmpty()) {
                dialog.cont.add("[scarlet]Failed to load mods").row();
                dialog.cont.add("[lightgray]Check your internet connection").row();
            } else {
                dialog.cont.add("[lime]Loaded " + mods.size + " mods").row();
                dialog.cont.row();
                
                int start = currentPage * MODS_PER_PAGE;
                int end = Math.min(start + MODS_PER_PAGE, mods.size);
                
                for (int i = start; i < end; i++) {
                    ModInfo mod = mods.get(i);
                    
                    Table modTable = new Table();
                    modTable.left();
                    
                    boolean watched = isInWatchlist(mod);
                    String prefix = watched ? "[accent]● " : "";
                    
                    modTable.button(prefix + "[white]" + mod.name, () -> showModDetails(mod))
                        .width(350f).left();
                    modTable.row();
                    
                    Label statusLabel = new Label("Loading...");
                    statusLabel.setColor(Color.yellow);
                    modTable.add(statusLabel).left().padLeft(10f);
                    
                    dialog.cont.add(modTable).fillX().row();
                    dialog.cont.image().color(Color.gray).fillX().height(1f).pad(5f).row();
                    
                    final int index = i;
                    float delay = (index - start) * 0.3f;
                    Timer.schedule(() -> {
                        fetchModStats(mod, stats -> {
                            if (stats.downloads >= 0) {
                                String text = formatNumber(stats.downloads) + " DL | " + 
                                            stats.releases + " releases";
                                statusLabel.setText(text);
                                statusLabel.setColor(Color.white);
                            } else {
                                statusLabel.setText("[scarlet]Failed / No releases");
                                statusLabel.setColor(Color.scarlet);
                            }
                        });
                    }, delay);
                }
                
                dialog.cont.row();
                Table navTable = new Table();
                int totalPages = (int) Math.ceil(mods.size / (float) MODS_PER_PAGE);
                
                if (currentPage > 0) {
                    navTable.button("< Prev", () -> {
                        currentPage--;
                        dialog.hide();
                        Timer.schedule(() -> showStatsDialog(), 0.1f);
                    }).size(100f, 50f);
                }
                
                navTable.add((currentPage + 1) + "/" + totalPages).padLeft(10f).padRight(10f);
                
                if (currentPage < totalPages - 1) {
                    navTable.button("Next >", () -> {
                        currentPage++;
                        dialog.hide();
                        Timer.schedule(() -> showStatsDialog(), 0.1f);
                    }).size(100f, 50f);
                }
                
                dialog.cont.add(navTable).row();
            }
        });
    }
    
    private void showModDetails(ModInfo mod) {
        BaseDialog dialog = new BaseDialog(mod.name);
        
        dialog.cont.add("[accent]" + mod.name).row();
        dialog.cont.add("[lightgray]" + mod.owner + "/" + mod.repo).row();
        dialog.cont.row();
        
        Table actionTable = new Table();
        actionTable.defaults().size(140f, 50f).pad(3f);
        
        actionTable.button(isInWatchlist(mod) ? "Watching" : "Watch", Icon.eye, () -> {
            toggleWatchlist(mod);
            dialog.hide();
            Timer.schedule(() -> showModDetails(mod), 0.1f);
        });
        
        dialog.cont.add(actionTable).row();
        dialog.cont.row();
        
        Label statusLabel = new Label("Loading statistics...");
        statusLabel.setColor(Color.yellow);
        dialog.cont.add(statusLabel).row();
        
        dialog.buttons.button("Back", dialog::hide).size(120f, 55f);
        dialog.show();
        
        fetchModStats(mod, stats -> {
            if (stats.downloads >= 0) {
                statusLabel.setText(formatNumber(stats.downloads) + " downloads | " + 
                                  stats.releases + " releases");
                statusLabel.setColor(Color.white);
            } else {
                statusLabel.setText("[scarlet]Failed to load statistics");
            }
        });
    }
    
    private void showNotificationsDialog() {
        BaseDialog dialog = new BaseDialog("Notifications");
        
        if (notifications.isEmpty()) {
            dialog.cont.add("[gray]No notifications yet").row();
            dialog.cont.add("[lightgray]Watch mods to get notifications!").row();
        } else {
            for (Notification notif : notifications) {
                Table t = new Table();
                t.add(notif.modName).left().row();
                t.add("[lightgray]" + notif.message).left().row();
                dialog.cont.add(t).fillX().row();
                dialog.cont.image().color(Color.gray).fillX().height(1f).row();
            }
        }
        
        dialog.buttons.button("Close", dialog::hide).size(120f, 55f);
        dialog.show();
    }
    
    private void showTutorialDialog() {
        BaseDialog dialog = new BaseDialog("Tutorial");
        
        dialog.cont.add("[accent]Welcome to ModInfo+ v1.5!").row();
        dialog.cont.add("[lightgray]Auto-discover Mindustry mods").row();
        dialog.cont.row();
        
        dialog.cont.add("[lime]✓ Auto-discovers mods from GitHub").left().row();
        dialog.cont.add("[lime]✓ Track downloads & stats").left().row();
        dialog.cont.add("[lime]✓ Watch mods for notifications").left().row();
        
        dialog.buttons.button("Close", dialog::hide).size(120f, 55f);
        dialog.show();
    }
        }
