package com.clawsaas.claw.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LegacyToolPolicySchemaCleaner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyToolPolicySchemaCleaner.class);
    private static final String TABLE = "agent_tool_policies";
    private static final String LEGACY_SHELL_APPROVAL_COLUMN = "sh" + "ell" + "_" + "approval";
    private static final String LEGACY_WEB_ACCESS_COLUMN = "web_access";
    private static final List<String> LEGACY_COLUMNS = List.of(
            LEGACY_SHELL_APPROVAL_COLUMN,
            LEGACY_WEB_ACCESS_COLUMN
    );

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public LegacyToolPolicySchemaCleaner(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        for (String column : LEGACY_COLUMNS) {
            if (!columnExists(column)) {
                continue;
            }
            jdbcTemplate.execute("ALTER TABLE " + TABLE + " DROP COLUMN " + column);
            log.info("Dropped legacy agent tool policy column {}.{}", TABLE, column);
        }
    }

    private boolean columnExists(String column) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            return columnExists(metaData, catalog, schema, column)
                    || columnExists(metaData, catalog, schema, column.toUpperCase());
        }
    }

    private boolean columnExists(DatabaseMetaData metaData, String catalog, String schema, String column) throws Exception {
        try (ResultSet columns = metaData.getColumns(catalog, schema, TABLE, column)) {
            if (columns.next()) {
                return true;
            }
        }
        try (ResultSet columns = metaData.getColumns(catalog, null, TABLE, column)) {
            return columns.next();
        }
    }
}
