package fhcampus.nilspetsch.tdms.service;

import fhcampus.nilspetsch.tdms.config.StorageProperties;
import fhcampus.nilspetsch.tdms.util.HashUtil;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class DatasetStorageService {
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final StorageProperties storageProperties;
    private final Path rootPath;

    public DatasetStorageService(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
        this.rootPath = Paths.get(storageProperties.getRoot()).toAbsolutePath().normalize();
        if (storageProperties.isCreateIfMissing()) {
            try {
                Files.createDirectories(rootPath);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to initialize storage directory.", e);
            }
        }
    }

    public StoredDataFile storeAsCsv(
        Long datasetId,
        int versionNumber,
        String schemaName,
        String tableName,
        List<Map<String, Object>> rows
    ) {
        Path relativePath = Paths.get(
            "datasets",
            "dataset-" + datasetId,
            "v" + versionNumber + "_" + schemaName + "_" + tableName + "_" + FILE_TS.format(LocalDateTime.now()) + ".csv"
        );
        Path absolutePath = rootPath.resolve(relativePath).normalize();
        try {
            Files.createDirectories(absolutePath.getParent());
            Files.write(absolutePath, toCsvBytes(rows));
            String checksum = HashUtil.sha256Hex(Files.readAllBytes(absolutePath));
            return new StoredDataFile(relativePath.toString().replace("\\", "/"), checksum, rows.size());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write dataset CSV file.", e);
        }
    }

    public StoredDataFile storeAsZipOfCsv(
        Long datasetId,
        int versionNumber,
        String schemaName,
        Map<String, List<Map<String, Object>>> tableRows
    ) {
        Path relativePath = Paths.get(
            "datasets",
            "dataset-" + datasetId,
            "v" + versionNumber + "_" + schemaName + "_bundle_" + FILE_TS.format(LocalDateTime.now()) + ".zip"
        );
        Path absolutePath = rootPath.resolve(relativePath).normalize();
        int totalRows = tableRows.values().stream().mapToInt(List::size).sum();

        try {
            Files.createDirectories(absolutePath.getParent());
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(absolutePath), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, List<Map<String, Object>>> entry : tableRows.entrySet()) {
                    zipOutputStream.putNextEntry(new ZipEntry(entry.getKey() + ".csv"));
                    zipOutputStream.write(toCsvBytes(entry.getValue()));
                    zipOutputStream.closeEntry();
                }
            }

            String checksum = HashUtil.sha256Hex(Files.readAllBytes(absolutePath));
            return new StoredDataFile(relativePath.toString().replace("\\", "/"), checksum, totalRows);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write dataset ZIP bundle.", e);
        }
    }

    public Resource loadAsResource(String relativePath) {
        Path absolutePath = rootPath.resolve(relativePath).normalize();
        if (!Files.exists(absolutePath)) {
            throw new IllegalArgumentException("Stored dataset file not found: " + relativePath);
        }
        return new FileSystemResource(absolutePath);
    }

    public Path storeWorkingCsv(
        String schemaName,
        String tableName,
        String purpose,
        List<Map<String, Object>> rows
    ) {
        Path workingDirectory = rootPath.resolve(Paths.get("working", "python-bridge")).normalize();
        Path filePath = workingDirectory.resolve(
            purpose + "_" + schemaName + "_" + tableName + "_" + FILE_TS.format(LocalDateTime.now()) + ".csv"
        );
        try {
            Files.createDirectories(workingDirectory);
            Files.write(
                filePath,
                toCsvBytes(rows),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            return filePath;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write working CSV file.", e);
        }
    }

    private byte[] toCsvBytes(List<Map<String, Object>> rows) throws IOException {
        Set<String> headerSet = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            headerSet.addAll(row.keySet());
        }
        List<String> headers = new ArrayList<>(headerSet);
        StringWriter stringWriter = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(stringWriter, CSVFormat.DEFAULT)) {
            printer.printRecord(headers);
            for (Map<String, Object> row : rows) {
                List<String> record = new ArrayList<>();
                for (String header : headers) {
                    Object value = row.get(header);
                    record.add(value == null ? null : String.valueOf(value));
                }
                printer.printRecord(record);
            }
            printer.flush();
        }
        return stringWriter.toString().getBytes(StandardCharsets.UTF_8);
    }
}
