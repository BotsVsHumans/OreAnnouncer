package com.alessiodp.oreannouncer.common.storage.dispatchers;

import com.alessiodp.core.common.ADPPlugin;
import com.alessiodp.core.common.configuration.Constants;
import com.alessiodp.core.common.storage.StorageType;
import com.alessiodp.core.common.storage.dispatchers.SQLDispatcher;
import com.alessiodp.core.common.storage.sql.ISQLTable;
import com.alessiodp.core.common.storage.sql.mysql.MySQLDao;
import com.alessiodp.core.common.storage.sql.mysql.MySQLHikariConfiguration;
import com.alessiodp.core.common.storage.sql.sqlite.SQLiteDao;
import com.alessiodp.oreannouncer.common.OreAnnouncerPlugin;
import com.alessiodp.oreannouncer.common.configuration.OAConstants;
import com.alessiodp.oreannouncer.common.configuration.data.ConfigMain;
import com.alessiodp.oreannouncer.common.players.objects.OAPlayerImpl;
import com.alessiodp.oreannouncer.common.players.objects.PlayerDataBlock;
import com.alessiodp.oreannouncer.common.storage.interfaces.IOADatabaseDispatcher;
import com.alessiodp.oreannouncer.common.storage.sql.SQLTable;
import com.alessiodp.oreannouncer.common.storage.sql.SQLUpgradeManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.UUID;

public class OASQLDispatcher extends SQLDispatcher implements IOADatabaseDispatcher {
	
	public OASQLDispatcher(ADPPlugin plugin) {
		super(plugin);
		upgradeManager = new SQLUpgradeManager();
	}
	
	@Override
	public void init(StorageType type) {
		switch (type) {
			case MYSQL:
				SQLTable.setupTables(
						OAConstants.VERSION_DATABASE_MYSQL,
						plugin.getResource("schemas/" + type.name().toLowerCase() + ".sql")
				);
				MySQLHikariConfiguration hikari = new MySQLHikariConfiguration(
						plugin.getPluginFallbackName(),
						ConfigMain.STORAGE_SETTINGS_MYSQL_ADDRESS,
						ConfigMain.STORAGE_SETTINGS_MYSQL_PORT,
						ConfigMain.STORAGE_SETTINGS_MYSQL_DATABASE,
						ConfigMain.STORAGE_SETTINGS_MYSQL_USERNAME,
						ConfigMain.STORAGE_SETTINGS_MYSQL_PASSWORD
				);
				hikari.setMaximumPoolSize(ConfigMain.STORAGE_SETTINGS_MYSQL_POOLSIZE);
				hikari.setMaxLifetime(ConfigMain.STORAGE_SETTINGS_MYSQL_CONNLIFETIME);
				hikari.setCharacterEncoding(ConfigMain.STORAGE_SETTINGS_MYSQL_CHARSET);
				hikari.setUseSSL(ConfigMain.STORAGE_SETTINGS_MYSQL_USESSL);
				database = new MySQLDao(plugin, hikari);
				database.initSQL();
				break;
			case SQLITE:
				SQLTable.setupTables(
						OAConstants.VERSION_DATABASE_SQLITE,
						plugin.getResource("schemas/" + type.name().toLowerCase() + ".sql")
				);
				database = new SQLiteDao(plugin, ConfigMain.STORAGE_SETTINGS_SQLITE_DBFILE);
				database.initSQL();
				break;
			default:
				// Unsupported storage type
		}
		
		if (database != null && !database.isFailed()) {
			databaseType = type;
			
			// Prepare tables list
			LinkedList<ISQLTable> tables = new LinkedList<>();
			tables.add(SQLTable.VERSIONS); // Version must be first
			tables.add(SQLTable.PLAYERS);
			tables.add(SQLTable.BLOCKS);
			
			try (Connection connection = getConnection()) {
				initTables(connection, tables);
			} catch (Exception ex) {
				plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR, ex);
			}
		}
	}
	
	@Override
	public void updatePlayer(OAPlayerImpl player) {
		try {
			boolean existData = false;
			if (!player.haveAlertsOn() || player.getDataBlocks().size() > 0)
				existData = true;
			
			try (Connection connection = getConnection()) {
				if (connection != null) {
					if (existData) {
						// Save data
						String query = OAConstants.QUERY_PLAYER_INSERT_SQLITE;
						if (databaseType == StorageType.MYSQL)
							query = OAConstants.QUERY_PLAYER_INSERT_MYSQL;
						try (PreparedStatement preStatement = connection.prepareStatement(SQLTable.formatGenericQuery(query))) {
							preStatement.setString(1, player.getPlayerUUID().toString());
							preStatement.setString(2, player.getName());
							preStatement.setBoolean(3, player.haveAlertsOn());
							
							preStatement.executeUpdate();
						}
					} else {
						// Remove data
						String query = OAConstants.QUERY_PLAYER_DELETE;
						
						try (PreparedStatement preStatement = connection.prepareStatement(SQLTable.formatGenericQuery(query))) {
							preStatement.setString(1, player.getPlayerUUID().toString());
							
							preStatement.executeUpdate();
						}
					}
				}
			}
		} catch (SQLException ex) {
			plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR, ex);
		}
	}
	
	@Override
	public OAPlayerImpl getPlayer(UUID playerUuid) {
		OAPlayerImpl ret = null;
		try (Connection connection = getConnection()) {
			if (connection != null) {
				ret = getPlayer(connection, playerUuid);
			}
		} catch (SQLException ex) {
			plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR, ex);
		}
		return ret;
	}
	
	@Override
	public OAPlayerImpl getPlayerByName(String playerName) {
		OAPlayerImpl ret = null;
		try (Connection connection = getConnection()) {
			if (connection != null) {
				String query = OAConstants.QUERY_PLAYER_GET_BYNAME;
				
				try (PreparedStatement preStatement = connection.prepareStatement(SQLTable.formatGenericQuery(query))) {
					preStatement.setString(1, playerName);
					
					try (ResultSet rs = preStatement.executeQuery()) {
						if (rs.next()) {
							ret = getPlayerFromResultSet(rs);
							if (ret != null)
								ret.loadBlocks(getPlayerBlocks(connection, ret.getPlayerUUID()));
						}
					}
				}
			}
		} catch (SQLException ex) {
			plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR, ex);
		}
		return ret;
	}
	
	@Override
	public ArrayList<OAPlayerImpl> getTopPlayersDestroyed(int limit, int offset) {
		ArrayList<OAPlayerImpl> list = new ArrayList<>();
		try (Connection connection = getConnection()) {
			if (connection != null) {
				String query = OAConstants.QUERY_PLAYER_TOP_BLOCKS;
				
				try (PreparedStatement preStatement = connection.prepareStatement(SQLTable.formatGenericQuery(query))) {
					preStatement.setInt(1, limit);
					preStatement.setInt(2, offset);
					
					try (ResultSet rs = preStatement.executeQuery()) {
						while (rs.next()) {
							try {
								OAPlayerImpl pl = getPlayer(connection, UUID.fromString(rs.getString("player")));
								
								if (pl != null) {
									list.add(pl);
								}
							} catch (IllegalArgumentException ex) {
								plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR_UUID
										.replace("{uuid}", rs.getString("player")), ex);
							}
						}
					}
				}
			}
		} catch (SQLException ex) {
			plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR, ex);
		}
		return list;
	}
	
	@Override
	public int getTopPlayersNumber() {
		int ret = 0;
		try (Connection connection = getConnection()) {
			if (connection != null) {
				String query = OAConstants.QUERY_PLAYER_TOP_NUMBER;
				
				try (
						Statement statement = connection.createStatement();
						ResultSet rs = statement.executeQuery(SQLTable.formatGenericQuery(query))
				) {
					if (rs.next()) {
						ret = rs.getInt("total");
					}
				}
			}
		} catch (SQLException ex) {
			plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR, ex);
		}
		return ret;
	}
	
	@Override
	public void updateDataBlock(PlayerDataBlock playerDataBlock) {
		String query = OAConstants.QUERY_BLOCK_INSERT_SQLITE;
		if (databaseType == StorageType.MYSQL)
			query = OAConstants.QUERY_BLOCK_INSERT_MYSQL;
		try (Connection connection = getConnection()) {
			if (connection != null) {
				try (PreparedStatement preStatement = connection.prepareStatement(SQLTable.formatGenericQuery(query))) {
					preStatement.setString(1, playerDataBlock.getPlayer().toString());
					preStatement.setString(2, playerDataBlock.getMaterialName());
					preStatement.setInt(3, playerDataBlock.getDestroyCount());
					
					preStatement.executeUpdate();
				}
			}
		} catch (SQLException ex) {
			plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR, ex);
		}
	}
	
	private OAPlayerImpl getPlayer(Connection connection, UUID playerUuid) {
		OAPlayerImpl ret = null;
		String query = OAConstants.QUERY_PLAYER_GET;
		
		try (PreparedStatement preStatement = connection.prepareStatement(SQLTable.formatGenericQuery(query))) {
			preStatement.setString(1, playerUuid.toString());
			
			try (ResultSet rs = preStatement.executeQuery()) {
				if (rs.next()) {
					ret = getPlayerFromResultSet(rs);
					if (ret != null)
						ret.loadBlocks(getPlayerBlocks(connection, ret.getPlayerUUID()));
				}
			}
		} catch (SQLException ex) {
			plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR, ex);
		}
		return ret;
	}
	
	private OAPlayerImpl getPlayerFromResultSet(ResultSet rs) {
		OAPlayerImpl ret = null;
		String uuid = "";
		try {
			uuid = rs.getString("uuid");
			ret = ((OreAnnouncerPlugin) plugin).getPlayerManager().initializePlayer(UUID.fromString(uuid));
			ret.setName(rs.getString("name"));
			ret.setAlertsOn(rs.getBoolean("alerts"));
		} catch (IllegalArgumentException ex) {
			plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR_UUID
					.replace("{uuid}", uuid), ex);
		} catch (Exception ex) {
			plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR, ex);
		}
		return ret;
	}
	
	private PlayerDataBlock getBlockFromResultSet(ResultSet rs) {
		PlayerDataBlock ret = null;
		String uuid = "";
		try {
			uuid = rs.getString("player");
			PlayerDataBlock pdb = new PlayerDataBlock();
			pdb.setPlayer(UUID.fromString(uuid));
			pdb.setMaterialName(rs.getString("material_name"));
			pdb.setDestroyCount(rs.getInt("destroyed"));
			
			ret = pdb;
		} catch (IllegalArgumentException ex) {
			plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR_UUID
					.replace("{uuid}", uuid), ex);
		} catch (Exception ex) {
			plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR, ex);
		}
		return ret;
	}
	
	private ArrayList<PlayerDataBlock> getPlayerBlocks(Connection connection, UUID playerUuid) {
		ArrayList<PlayerDataBlock> ret = new ArrayList<>();
		String query = OAConstants.QUERY_BLOCK_GET_PLAYER;
		
		try (PreparedStatement preStatement = connection.prepareStatement(SQLTable.formatGenericQuery(query))) {
			preStatement.setString(1, playerUuid.toString());
			
			try (ResultSet rs = preStatement.executeQuery()) {
				while (rs.next()) {
					ret.add(getBlockFromResultSet(rs));
				}
			}
		} catch (Exception ex) {
			plugin.getLoggerManager().printErrorStacktrace(Constants.DEBUG_SQL_ERROR, ex);
		}
		return ret;
	}
}
