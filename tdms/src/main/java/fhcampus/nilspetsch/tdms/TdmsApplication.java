package fhcampus.nilspetsch.tdms;

import fhcampus.nilspetsch.tdms.config.StorageProperties;
import fhcampus.nilspetsch.tdms.config.SyntheticPythonServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({StorageProperties.class, SyntheticPythonServiceProperties.class})
public class TdmsApplication {

	public static void main(String[] args) {
		SpringApplication.run(TdmsApplication.class, args);
	}

}
