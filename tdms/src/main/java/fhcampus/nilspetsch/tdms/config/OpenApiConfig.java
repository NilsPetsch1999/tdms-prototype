package fhcampus.nilspetsch.tdms.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tdmsOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("TDMS API")
                .description("Test Data Management System prototype API (MySQL only).")
                .version("v1")
                .contact(new Contact().name("TDMS Prototype"))
                .license(new License().name("Academic prototype - non-production use")));
    }
}
