package fhcampus.nilspetsch.tdms.repo;

import fhcampus.nilspetsch.tdms.domain.Dataset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DatasetRepository extends JpaRepository<Dataset, Long> {
    Optional<Dataset> findByName(String name);
}
