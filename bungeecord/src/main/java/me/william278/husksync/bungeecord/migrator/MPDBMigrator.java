package me.william278.husksync.bungeecord.migrator;

import me.william278.husksync.HuskSyncBungeeCord;
import me.william278.husksync.PlayerData;
import me.william278.husksync.Settings;
import me.william278.husksync.bungeecord.data.DataManager;
import me.william278.husksync.bungeecord.data.sql.Database;
import me.william278.husksync.bungeecord.data.sql.MySQL;
import me.william278.husksync.migrator.MPDBPlayerData;
import me.william278.husksync.redis.RedisMessage;
import net.md_5.bungee.api.ProxyServer;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Class to handle migration of data from MySQLPlayerDataBridge
 * <p>
 * The migrator accesses and decodes MPDB's format directly.
 * It does this by establishing a connection
 */
public class MPDBMigrator {

    public static int migratedDataSent = 0;
    public static int playersMigrated = 0;

    private static final HuskSyncBungeeCord plugin = HuskSyncBungeeCord.getInstance();

    public static HashMap<PlayerData,String> incomingPlayerData;

    public static MigrationSettings migrationSettings = new MigrationSettings();
    private static Database sourceDatabase;

    private static HashSet<MPDBPlayerData> mpdbPlayerData;

    public void start() {
        if (ProxyServer.getInstance().getPlayers().size() > 0) {
            plugin.getLogger().log(Level.WARNING, "Failed to start migration because there are players online. " +
                    "Your network has to be empty to migrate data for safety reasons.");
            return;
        }

        int synchronisedServersWithMpdb = 0;
        for (HuskSyncBungeeCord.Server server : HuskSyncBungeeCord.synchronisedServers) {
            if (server.hasMySqlPlayerDataBridge()) {
                synchronisedServersWithMpdb++;
            }
        }
        if (synchronisedServersWithMpdb < 1) {
            plugin.getLogger().log(Level.WARNING, "Failed to start migration because at least one Spigot server with both HuskSync and MySqlPlayerDataBridge installed is not online. " +
                    "Please start one Spigot server with HuskSync installed to begin migration.");
            return;
        }

        migratedDataSent = 0;
        playersMigrated = 0;
        mpdbPlayerData = new HashSet<>();
        incomingPlayerData = new HashMap<>();
        final MigrationSettings settings = migrationSettings;

        // Get connection to source database
        sourceDatabase = new MigratorMySQL(plugin, settings.sourceHost, settings.sourcePort,
                settings.sourceDatabase, settings.sourceUsername, settings.sourcePassword);
        sourceDatabase.load();
        if (sourceDatabase.isInactive()) {
            plugin.getLogger().log(Level.WARNING, "Failed to establish connection to the origin MySQL database. " +
                    "Please check you have input the correct connection details and try again.");
            return;
        }

        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            prepareTargetDatabase();

            getInventoryData();

            getEnderChestData();

            getExperienceData();

            sendEncodedData();
        });
    }

    // Clear the new database out of current data
    private void prepareTargetDatabase() {
        plugin.getLogger().log(Level.INFO, "Preparing target database...");
        try (Connection connection = HuskSyncBungeeCord.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + Database.PLAYER_TABLE_NAME + ";")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + Database.DATA_TABLE_NAME + ";")) {
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "An exception occurred preparing the target database", e);
        } finally {
            plugin.getLogger().log(Level.INFO, "Finished preparing target database!");
        }
    }

    private void getInventoryData() {
        plugin.getLogger().log(Level.INFO, "Getting inventory data from MySQLPlayerDataBridge...");
        try (Connection connection = sourceDatabase.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + migrationSettings.inventoryDataTable + ";")) {
                ResultSet resultSet = statement.executeQuery();
                while (resultSet.next()) {
                    final UUID playerUUID = UUID.fromString(resultSet.getString("player_uuid"));
                    final String playerName = resultSet.getString("player_name");

                    MPDBPlayerData data = new MPDBPlayerData(playerUUID, playerName);
                    data.inventoryData = resultSet.getString("inventory");
                    data.armorData = resultSet.getString("armor");

                    mpdbPlayerData.add(data);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "An exception occurred getting inventory data", e);
        } finally {
            plugin.getLogger().log(Level.INFO, "Finished getting inventory data from MySQLPlayerDataBridge");
        }
    }

    private void getEnderChestData() {
        plugin.getLogger().log(Level.INFO, "Getting ender chest data from MySQLPlayerDataBridge...");
        try (Connection connection = sourceDatabase.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + migrationSettings.enderChestDataTable + ";")) {
                ResultSet resultSet = statement.executeQuery();
                while (resultSet.next()) {
                    final UUID playerUUID = UUID.fromString(resultSet.getString("player_uuid"));

                    for (MPDBPlayerData data : mpdbPlayerData) {
                        if (data.playerUUID.equals(playerUUID)) {
                            data.enderChestData = resultSet.getString("enderchest");
                            break;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "An exception occurred getting ender chest data", e);
        } finally {
            plugin.getLogger().log(Level.INFO, "Finished getting ender chest data from MySQLPlayerDataBridge");
        }
    }

    private void getExperienceData() {
        plugin.getLogger().log(Level.INFO, "Getting experience data from MySQLPlayerDataBridge...");
        try (Connection connection = sourceDatabase.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + migrationSettings.expDataTable + ";")) {
                ResultSet resultSet = statement.executeQuery();
                while (resultSet.next()) {
                    final UUID playerUUID = UUID.fromString(resultSet.getString("player_uuid"));

                    for (MPDBPlayerData data : mpdbPlayerData) {
                        if (data.playerUUID.equals(playerUUID)) {
                            data.expLevel = resultSet.getInt("exp_lvl");
                            data.expProgress = resultSet.getFloat("exp");
                            data.totalExperience = resultSet.getInt("total_exp");
                            break;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "An exception occurred getting experience data", e);
        } finally {
            plugin.getLogger().log(Level.INFO, "Finished getting experience data from MySQLPlayerDataBridge");
        }
    }

    private void sendEncodedData() {
        for (HuskSyncBungeeCord.Server processingServer : HuskSyncBungeeCord.synchronisedServers) {
            if (processingServer.hasMySqlPlayerDataBridge()) {
                for (MPDBPlayerData data : mpdbPlayerData) {
                    try {
                        new RedisMessage(RedisMessage.MessageType.DECODE_MPDB_DATA,
                                new RedisMessage.MessageTarget(Settings.ServerType.BUKKIT, null),
                                processingServer.serverUUID().toString(),
                                RedisMessage.serialize(data))
                                .send();
                        migratedDataSent++;
                    } catch (IOException e) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to serialize encoded MPDB data", e);
                    }
                }
                plugin.getLogger().log(Level.INFO, "Finished dispatching encoded data for " + migratedDataSent + " players; please wait for conversion to finish");
            }
            return;
        }
    }

    /**
     * Load all incoming decoded MPDB data to cache / SQL
     */
    public static void loadIncomingData() {
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            int playersSaved = 0;
            plugin.getLogger().log(Level.INFO, "Saving data for " + playersMigrated + " players...");

            for (PlayerData playerData : incomingPlayerData.keySet()) {
                String playerName = incomingPlayerData.get(playerData);

                // Add the player to the MySQL table
                DataManager.ensurePlayerExists(playerData.getPlayerUUID(), playerName);

                // Update the data in the cache and SQL
                DataManager.updatePlayerData(playerData);

                playersSaved++;
                plugin.getLogger().log(Level.INFO, "Saved data for " + playersSaved + "/" + playersMigrated + " players");
            }

            // Mark as done when done
            plugin.getLogger().log(Level.INFO, """
                                === MySQLPlayerDataBridge Migration Wizard ==========
                                                                
                                Migration complete!
                                                                
                                Successfully migrated data for %1%/%2% players.
                                                                
                                You should now uninstall MySQLPlayerDataBridge from
                                the rest of the Spigot servers, then restart them.
                                """.replaceAll("%1%", Integer.toString(MPDBMigrator.playersMigrated))
                    .replaceAll("%2%", Integer.toString(MPDBMigrator.migratedDataSent)));
            sourceDatabase.close(); // Close source database
        });
    }

    /**
     * Class used to hold settings for the MPDB migration
     */
    public static class MigrationSettings {
        public String sourceHost;
        public int sourcePort;
        public String sourceDatabase;
        public String sourceUsername;
        public String sourcePassword;

        public String inventoryDataTable;
        public String enderChestDataTable;
        public String expDataTable;

        public MigrationSettings() {
            sourceHost = "localhost";
            sourcePort = 3306;
            sourceDatabase = "mpdb";
            sourceUsername = "root";
            sourcePassword = "pa55w0rd";

            inventoryDataTable = "mpdb_inventory";
            enderChestDataTable = "mpdb_enderchest";
            expDataTable = "mpdb_experience";
        }
    }

    /**
     * MySQL class used for importing data from MPDB
     */
    public static class MigratorMySQL extends MySQL {
        public MigratorMySQL(HuskSyncBungeeCord instance, String host, int port, String database, String username, String password) {
            super(instance);
            super.host = host;
            super.port = port;
            super.database = database;
            super.username = username;
            super.password = password;
            super.params = "?useSSL=false";
            super.dataPoolName = DATA_POOL_NAME + "Migrator";
        }
    }

}
