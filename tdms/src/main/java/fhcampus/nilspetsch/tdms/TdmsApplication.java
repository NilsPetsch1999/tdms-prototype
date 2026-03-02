package fhcampus.nilspetsch.tdms;

import fhcampus.nilspetsch.tdms.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(StorageProperties.class)
public class TdmsApplication {

	public static void main(String[] args) {
		SpringApplication.run(TdmsApplication.class, args);
	}

}
