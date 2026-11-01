package com.marketchat.commerce.catalog.infrastructure;

import com.marketchat.commerce.catalog.application.GetMerchantUseCase;
import com.marketchat.commerce.catalog.application.GetProductUseCase;
import com.marketchat.commerce.catalog.application.SearchCatalogUseCase;
import com.marketchat.commerce.catalog.domain.port.CatalogRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the framework-free use cases (application layer has no Spring annotations). */
@Configuration
class CatalogConfiguration {

    @Bean
    SearchCatalogUseCase searchCatalogUseCase(CatalogRepository repository, Clock clock) {
        return new SearchCatalogUseCase(repository, clock);
    }

    @Bean
    GetMerchantUseCase getMerchantUseCase(CatalogRepository repository, Clock clock) {
        return new GetMerchantUseCase(repository, clock);
    }

    @Bean
    GetProductUseCase getProductUseCase(CatalogRepository repository, Clock clock) {
        return new GetProductUseCase(repository, clock);
    }
}
