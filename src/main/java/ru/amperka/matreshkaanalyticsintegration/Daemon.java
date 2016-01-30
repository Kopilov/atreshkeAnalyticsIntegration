package ru.amperka.matreshkaanalyticsintegration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/**
 * Фоновый процесс, осуществляющий сбор данных из Google Analytics и запись их
 * в базу данных для процесса, взаимодействующего со встраиваемой электроникой.
 * 
 * @author aleksandr<kopilov.ad@gmail.com>
 */
public class Daemon {
	private static final ResourceBundle l10n = ResourceBundle.getBundle("ru.amperka.matreshkaanalyticsintegration.l10n");
	private static final Logger logger = Logger.getLogger(Daemon.class.getName());
//	static {
//		logger.setLevel(Level.FINEST);
//		ConsoleHandler handler = new ConsoleHandler();
//		// PUBLISH this level
//		handler.setLevel(Level.FINEST);
//		logger.addHandler(handler);
//	}
	
	private final String connectionString, login, password;
	
	/**
	 * Парсинг и валидация входных параметров, запуск демона
	 * @param args параметры командной строки
	 */
	public static void main(String[] args) {
		CommandLine commandLine;
		try {
			commandLine = parseCommandLine(args);
			List<String> argList = commandLine.getArgList();
			if (commandLine.hasOption("help") || argList.isEmpty()) {
				System.out.println(l10n.getString("detailedHelp"));
				return;
			}
		} catch (ParseException ex) {
			System.out.println(ex.getLocalizedMessage());
			System.out.println(l10n.getString("help"));
			return;
		}
		Daemon daemon = new Daemon(commandLine);
		try {
			daemon.run();
		} catch (SQLException | InterruptedException ex) {
			Logger.getLogger(Daemon.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	/**
	 * Парсинг параметров командной строки с использованием Apache Commons CLI.
	 * Ищутся опции "login", "password", "icon", "help"
	 * @param args параметры командной строки
	 * @return
	 * @throws ParseException 
	 */
	private static CommandLine parseCommandLine(String[] args) throws ParseException {
		Options options = new Options();
		options.addOption(null, "login", true, "Database login");
		options.addOption(null, "password", true, "Database password");
		options.addOption("i", "icon", false, "Display tray icon");
		options.addOption("h", "help", false, "Show help message and exit");
		CommandLineParser parser = new DefaultParser();
		return parser.parse(options, args);
	}
	
	private Daemon(CommandLine commandLine) {
		connectionString = commandLine.getArgList().get(0);
		login = commandLine.getOptionValue("login");
		password = commandLine.getOptionValue("password");
	}
	
	private synchronized void run() throws SQLException, InterruptedException {
		Connection connectionRead = DriverManager.getConnection(connectionString, login, password);
		Connection connectionWrite = DriverManager.getConnection(connectionString, login, password);
		PreparedStatement getActiveResources = connectionRead.prepareStatement(
				"select ID, URL, KEY_FILE_LOCATION, SERVICE_ACCOUNT_EMAIL, LAST_UPDATED, UPDATING_PERIOD from WEBRESOURCE\n" +
				"where IS_ACTIVE > 0 and (current_timestamp - LAST_UPDATED) * 24 * 60 * 60 > UPDATING_PERIOD");
		PreparedStatement updateResource = connectionWrite.prepareStatement(
				"update WEBRESOURCE set LAST_UPDATED = current_timestamp, USERS_ONLINE = ? where ID = ?");
		while (true) {
			getActiveResources.clearParameters();
			ResultSet resultSet = getActiveResources.executeQuery();
			while (resultSet.next()) {
				String serviceAccountEmail = resultSet.getString("SERVICE_ACCOUNT_EMAIL");
				String keyFileLocation = resultSet.getString("KEY_FILE_LOCATION");
				String url = resultSet.getString("URL");
				int usersOnline = HelloAnalyticsRealtime.getUsersOnline(serviceAccountEmail, keyFileLocation, url);
				logger.log(Level.FINE, "Online on {0}: {1}", new Object[]{url, usersOnline});
				updateResource.clearParameters();
				updateResource.setInt(1, usersOnline);
				updateResource.setInt(2, resultSet.getInt("ID"));
				updateResource.execute();
			}
			this.wait(500);
		}
	}

}