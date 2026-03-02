package fhcampus.nilspetsch.tdms.repo;

import fhcampus.nilspetsch.tdms.domain.DatasetVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DatasetVersionRepository extends JpaRepository<DatasetVersion, Long> {
    Optional<DatasetVersion> findTopByDatasetIdOrderByVersionNumberDesc(Long datasetId);

    List<DatasetVersion> findByDatasetIdOrderByVersionNumberDesc(Long datasetId);

    Optional<DatasetVersion> findByDatasetIdAndVersionNumber(Long datasetId, Integer versionNumber);
}
