package fhcampus.nilspetsch.tdms.service;

import fhcampus.nilspetsch.tdms.api.SourceConnectionRequest;
import fhcampus.nilspetsch.tdms.api.SourceConnectionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SourceDatabaseConnectionService {

    private final AtomicReference<ConnectionSettings> currentSettings;

    public SourceDatabaseConnectionService(
        @Value("${tdms.source-database.url:${spring.datasource.url:}}") String url,
        @Value("${tdms.source-database.username:${spring.datasource.username:}}") String username,
        @Value("${tdms.source-database.password:${spring.datasource.password:}}") String password,
        @Value("${tdms.source-database.driver-class-name:${spring.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}}") String driverClassName
    ) {
        this.currentSettings = new AtomicReference<>(new ConnectionSettings(url, username, password, driverClassName));
    }

    public SourceConnectionResponse describeCurrentConnection() {
        return describe(currentSettings.get());
    }

    public SourceConnectionResponse updateConnection(SourceConnectionRequest request) {
        ConnectionSettings candidate = new ConnectionSettings(
            request.url().trim(),
            request.username().trim(),
            request.password(),
            request.driverClassName().trim()
        );
        describe(candidate);
        currentSettings.set(candidate);
        return describe(candidate);
    }

    public JdbcTemplate createJdbcTemplate() {
        return new JdbcTemplate(createDataSource(currentSettings.get()));
    }

    public boolean isMySql() {
        try (Connection connection = openConnection(currentSettings.get())) {
            String productName = connection.getMetaData().getDatabaseProductName();
            return productName != null && productName.toLowerCase().contains("mysql");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to detect source database type.", e);
        }
    }

    private SourceConnectionResponse describe(ConnectionSettings settings) {
        try (Connection connection = openConnection(settings)) {
            DatabaseMetaData metaData = connection.getMetaData();
            return new SourceConnectionResponse(
                settings.url(),
                settings.username(),
                settings.password(),
                settings.driverClassName(),
                metaData.getDatabaseProductName(),
                metaData.getDatabaseProductVersion(),
                connection.getCatalog()
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to connect to the source database: " + e.getMessage(), e);
        }
    }

    private Connection openConnection(ConnectionSettings settings) throws SQLException, ClassNotFoundException {
        Class.forName(settings.driverClassName());
        return createDataSource(settings).getConnection();
    }

    private DataSource createDataSource(ConnectionSettings settings) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(settings.url());
        dataSource.setUsername(settings.username());
        dataSource.setPassword(settings.password());
        dataSource.setDriverClassName(settings.driverClassName());
        return dataSource;
    }

    private record ConnectionSettings(
        String url,
        String username,
        String password,
        String driverClassName
    ) {
        private ConnectionSettings {
            if (isBlank(url)) {
                throw new IllegalArgumentException("Source database URL must not be blank.");
            }
            if (isBlank(username)) {
                throw new IllegalArgumentException("Source database username must not be blank.");
            }
            if (isBlank(driverClassName)) {
                throw new IllegalArgumentException("Source database driver class name must not be blank.");
            }
            password = Objects.requireNonNullElse(password, "");
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
