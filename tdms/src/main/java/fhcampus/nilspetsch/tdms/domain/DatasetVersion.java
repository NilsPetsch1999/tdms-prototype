package fhcampus.nilspetsch.tdms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
    name = "dataset_versions",
    uniqueConstraints = @UniqueConstraint(columnNames = {"dataset_id", "version_number"})
)
public class DatasetVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "dataset_id", nullable = false)
    private Dataset dataset;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "schema_name", nullable = false, length = 128)
    private String schemaName;

    @Column(name = "table_name", nullable = false, length = 128)
    private String tableName;

    @Column(name = "schema_version", length = 64)
    private String schemaVersion;

    @Lob
    @Column(name = "generation_parameters_json", columnDefinition = "LONGTEXT")
    private String generationParametersJson;

    @Lob
    @Column(name = "masking_rules_json", columnDefinition = "LONGTEXT")
    private String maskingRulesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_format", nullable = false, length = 16)
    private FileFormat fileFormat = FileFormat.CSV;

    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "row_count", nullable = false)
    private Integer rowCount;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Dataset getDataset() {
        return dataset;
    }

    public void setDataset(Dataset dataset) {
        this.dataset = dataset;
    }

    public Integer getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(Integer versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getGenerationParametersJson() {
        return generationParametersJson;
    }

    public void setGenerationParametersJson(String generationParametersJson) {
        this.generationParametersJson = generationParametersJson;
    }

    public String getMaskingRulesJson() {
        return maskingRulesJson;
    }

    public void setMaskingRulesJson(String maskingRulesJson) {
        this.maskingRulesJson = maskingRulesJson;
    }

    public FileFormat getFileFormat() {
        return fileFormat;
    }

    public void setFileFormat(FileFormat fileFormat) {
        this.fileFormat = fileFormat;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public void setChecksumSha256(String checksumSha256) {
        this.checksumSha256 = checksumSha256;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
