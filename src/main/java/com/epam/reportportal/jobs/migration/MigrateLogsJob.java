package com.epam.reportportal.jobs.migration;

import com.epam.reportportal.elastic.SimpleElasticSearchClient;
import com.epam.reportportal.jobs.BaseJob;
import com.epam.reportportal.log.LogMessage;
import com.epam.reportportal.model.LogRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MigrateLogsJob extends BaseJob {
	private final SimpleElasticSearchClient elasticSearchClient;
	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
	private static final String SELECT_LAST_LOG_TIME = "SELECT MAX(log_time) FROM log";
	private static final String SELECT_ALL_LOGS_WITH_LAUNCH_ID = "SELECT id, log_time, log_message, item_id, launch_id, project_id FROM log WHERE launch_id IS NOT NULL ORDER BY log_time ";
	private static final String SELECT_ALL_LOGS_WITHOUT_LAUNCH_ID =
			"SELECT l.id, log_time, log_message, l.item_id AS item_id, ti.launch_id AS launch_id, project_id FROM log l "
					+ "JOIN test_item ti ON l.item_id = ti.item_id WHERE ti.launch_id IN (:ids) "
					+ "UNION SELECT l.id, log_time, log_message, l.item_id AS item_id, ti.launch_id AS launch_id, project_id FROM log l "
					+ "JOIN test_item ti ON l.item_id = ti.item_id WHERE retry_of IS NOT NULL AND retry_of IN (SELECT item_id FROM test_item "
					+ "WHERE launch_id IN (:ids))";
	private static final String SELECT_LOGS_WITH_LAUNCH_ID_AFTER_DATE =
			"SELECT id, log_time, log_message, item_id, launch_id, project_id FROM log WHERE launch_id IS NOT NULL AND log_time >= ?"
					+ " ORDER BY log_time";
	private static final String SELECT_LOGS_WITHOUT_LAUNCH_ID_AFTER_DATE =
			"SELECT l.id, log_time, log_message, l.item_id AS item_id, ti.launch_id AS launch_id, project_id FROM log l "
					+ "JOIN test_item ti ON l.item_id = ti.item_id WHERE l.log_time >= :time AND ti.launch_id IN (:ids) "
					+ "UNION SELECT l.id, log_time, log_message, l.item_id AS item_id, ti.launch_id AS launch_id, project_id FROM log l "
					+ "JOIN test_item ti ON l.item_id = ti.item_id WHERE retry_of IS NOT NULL AND retry_of IN (SELECT item_id FROM test_item "
					+ "WHERE launch_id IN (:ids))";

	public MigrateLogsJob(JdbcTemplate jdbcTemplate, SimpleElasticSearchClient elasticSearchClient) {
		super(jdbcTemplate);
		this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
		this.elasticSearchClient = elasticSearchClient;
	}

	@Scheduled(cron = "${rp.environment.variable.migrate.log.cron}")
	//@SchedulerLock(name = "migrateLogs", lockAtMostFor = "24h")
	public void execute() {
		logStart();
		Timestamp lastLogTimestamp = jdbcTemplate.queryForObject(SELECT_LAST_LOG_TIME, Timestamp.class);
		if (lastLogTimestamp == null) {
			return;
		}
		LocalDateTime lastLogTime = lastLogTimestamp.toLocalDateTime();
		LOGGER.info("Last log from Postgres : {}", lastLogTime);
		Optional<LogMessage> lastLogFromElastic = elasticSearchClient.getLastLogFromElasticSearch();
		if (lastLogFromElastic.isEmpty()) {
			migrateAllLogs();
			return;
		}
		LOGGER.info("Last log from Elastic : {}", lastLogFromElastic.get());
		LocalDateTime elasticLastLogTime = lastLogFromElastic.get().getLogTime();
		if (elasticLastLogTime == null) {
			migrateAllLogs();
			return;
		}
		int comparisonResult = lastLogTime.compareTo(elasticLastLogTime);
		if (comparisonResult == 0) {
			LOGGER.info("Elastic has the same logs as Postgres");
			logFinish();
		} else if (comparisonResult < 0) {
			LOGGER.info("Drop logs from ES after {}", lastLogTime);
			elasticSearchClient.deleteLogsAfterDate(lastLogTime);
			logFinish();
		} else {
			migrateLogsAfterDate(elasticLastLogTime);
		}
	}

	private void migrateAllLogs() {
		LOGGER.info("Migrating all logs from Postgres");
		List<LogMessage> logMessageWithLaunchIdList = jdbcTemplate.query(SELECT_ALL_LOGS_WITH_LAUNCH_ID, new LogRowMapper());
		List<Long> launchIds = logMessageWithLaunchIdList.stream().map(LogMessage::getLaunchId).distinct().collect(Collectors.toList());
		List<LogMessage> logMessageWithoutLaunchIdList = namedParameterJdbcTemplate.query(SELECT_ALL_LOGS_WITHOUT_LAUNCH_ID,
				Map.of("ids", launchIds),
				new LogRowMapper()
		);
		elasticSearchClient.save(createIndexMap(logMessageWithLaunchIdList, logMessageWithoutLaunchIdList));
		logFinish();
	}

	private void migrateLogsAfterDate(LocalDateTime date) {
		LOGGER.info("Migrating logs after {}", date);
		List<LogMessage> logMessageWithLaunchIdList = jdbcTemplate.query(SELECT_LOGS_WITH_LAUNCH_ID_AFTER_DATE, new LogRowMapper(), date);
		List<Long> launchIds = logMessageWithLaunchIdList.stream().map(LogMessage::getLaunchId).distinct().collect(Collectors.toList());
		List<LogMessage> logMessageWithoutLaunchIdList = namedParameterJdbcTemplate.query(SELECT_LOGS_WITHOUT_LAUNCH_ID_AFTER_DATE,
				Map.of("ids", launchIds, "time", date),
				new LogRowMapper()
		);
		elasticSearchClient.save(createIndexMap(logMessageWithLaunchIdList, logMessageWithoutLaunchIdList));
		logFinish();
	}

	private Map<Long, List<LogMessage>> createIndexMap(List<LogMessage> logsWithLaunchId, List<LogMessage> logsWithoutLaunchId) {
		Map<Long, List<LogMessage>> indexMap = new HashMap<>();
		for (LogMessage logMessage : logsWithLaunchId) {
			List<LogMessage> logMessageList = new ArrayList<>();
			logMessageList.add(logMessage);
			indexMap.put(logMessage.getLaunchId(), logMessageList);
		}
		for (LogMessage logMessage : logsWithoutLaunchId) {
			indexMap.get(logMessage.getLaunchId()).add(logMessage);
		}
		return indexMap;
	}
}
