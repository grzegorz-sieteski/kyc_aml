package com.gs5.kycaml.risk.adapters.config;

import com.gs5.kycaml.risk.adapters.events.OutboxRelay;
import com.gs5.kycaml.risk.adapters.projection.ViewProjector;
import com.gs5.kycaml.risk.domain.BookingCentres;
import com.gs5.kycaml.risk.domain.ComplianceRiskViewReader;
import com.gs5.kycaml.risk.domain.EvaluateCustomerRiskUseCase;
import com.gs5.kycaml.risk.domain.FrontOfficeStatusReader;
import com.gs5.kycaml.risk.domain.GetComplianceRiskViewUseCase;
import com.gs5.kycaml.risk.domain.GetFrontOfficeStatusUseCase;
import com.gs5.kycaml.risk.domain.RecordComplianceDecisionUseCase;
import com.gs5.kycaml.risk.domain.RiskProfileIdGenerator;
import com.gs5.kycaml.risk.domain.RiskProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;

/**
 * Wires the risk module: the use cases are plain classes of {@code domain/}, so this is the one
 * place that gives them their ports.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RiskProperties.class)
class RiskModuleConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RiskProfileIdGenerator riskProfileIdGenerator() {
        return new RandomRiskProfileIdGenerator();
    }

    @Bean
    BookingCentres bookingCentres(RiskProperties properties) {
        return tenantId -> Optional.ofNullable(properties.bookingCentres().get(tenantId));
    }

    @Bean
    EvaluateCustomerRiskUseCase evaluateCustomerRiskUseCase(RiskProfileRepository repository,
                                                            BookingCentres bookingCentres,
                                                            RiskProfileIdGenerator ids, Clock clock) {
        return new EvaluateCustomerRiskUseCase(repository, bookingCentres, ids, clock);
    }

    @Bean
    RecordComplianceDecisionUseCase recordComplianceDecisionUseCase(RiskProfileRepository repository,
                                                                    RiskProfileIdGenerator ids, Clock clock) {
        return new RecordComplianceDecisionUseCase(repository, ids, clock);
    }

    @Bean
    GetComplianceRiskViewUseCase getComplianceRiskViewUseCase(ComplianceRiskViewReader reader) {
        return new GetComplianceRiskViewUseCase(reader);
    }

    @Bean
    GetFrontOfficeStatusUseCase getFrontOfficeStatusUseCase(FrontOfficeStatusReader reader) {
        return new GetFrontOfficeStatusUseCase(reader);
    }

    @Bean
    OutboxRelay outboxRelay(JdbcClient jdbc, ViewProjector projector, TransactionTemplate transaction, Clock clock,
                            @Value("${app.outbox.relay.batch-size}") int batchSize) {
        return new OutboxRelay(jdbc, projector, transaction, clock, batchSize);
    }
}
