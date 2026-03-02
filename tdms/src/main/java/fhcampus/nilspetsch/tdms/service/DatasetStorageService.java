package fhcampus.nilspetsch.tdms.service;

import fhcampus.nilspetsch.tdms.config.StorageProperties;
import fhcampus.nilspetsch.tdms.util.HashUtil;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        Set<String> headerSet = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            headerSet.addAll(row.keySet());
        }
        List<String> headers = new ArrayList<>(headerSet);
        Path relativePath = Paths.get(
            "datasets",
            "dataset-" + datasetId,
            "v" + versionNumber + "_" + schemaName + "_" + tableName + "_" + FILE_TS.format(LocalDateTime.now()) + ".csv"
        );
        Path absolutePath = rootPath.resolve(relativePath).normalize();
        try {
            Files.createDirectories(absolutePath.getParent());
            try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(absolutePath, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
                printer.printRecord(headers);
                for (Map<String, Object> row : rows) {
                    List<String> record = new ArrayList<>();
                    for (String header : headers) {
                        Object value = row.get(header);
                        record.add(value == null ? null : String.valueOf(value));
                    }
                    printer.printRecord(record);
                }
            }
            String checksum = HashUtil.sha256Hex(Files.readAllBytes(absolutePath));
            return new StoredDataFile(relativePath.toString().replace("\\", "/"), checksum, rows.size());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write dataset CSV file.", e);
        }
    }

    public Resource loadAsResource(String relativePath) {
        Path absolutePath = rootPath.resolve(relativePath).normalize();
        if (!Files.exists(absolutePath)) {
            throw new IllegalArgumentException("Stored dataset file not found: " + relativePath);
        }
        return new FileSystemResource(absolutePath);
    }
}
