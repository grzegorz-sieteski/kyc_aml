package com.gs5.kycaml.risk.adapters.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.gs5.kycaml.risk.adapters.projection.ViewProjector;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour 3 (configuration) and the outline of the relay's public contract. Needs no database: it checks
 * what stage 4 must expose. The behaviour itself is pinned by the other {@code Outbox*Test} classes.
 */
class OutboxRelayContractTest {

    @Test
    @DisplayName("the relay is built with a batch size, a connection budget and a lease duration")
    void shouldTakeBudgetAndLeaseAsConfiguration() {
        var constructors = List.of(OutboxRelay.class.getConstructors());

        assertThat(constructors)
                .anySatisfy(constructor -> assertThat(constructor.getParameterTypes()).containsExactly(
                        JdbcClient.class, ViewProjector.class, TransactionTemplate.class, Clock.class,
                        int.class, int.class, Duration.class));
    }

    @ParameterizedTest(name = "pool of {0} gives the relay a budget of {1}")
    @CsvSource({"1,2", "2,2", "3,2", "4,2", "5,2", "6,3", "10,5", "11,5", "20,10", "100,50"})
    @DisplayName("the default connection budget is half the pool, and never fewer than two")
    void shouldDefaultTheBudgetToHalfThePool(int pool, int budget) throws ReflectiveOperationException {
        var method = OutboxRelay.class.getMethod("defaultConnectionBudget", int.class);

        assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        assertThat(method.invoke(null, pool)).isEqualTo(budget);
    }

    @Test
    @DisplayName("the lease duration is a setting of the application, long enough to outlive the slowest chunk")
    void shouldConfigureTheLease() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        var lease = yaml.getObject().getProperty("app.outbox.relay.lease");

        assertThat(lease).as("app.outbox.relay.lease in application.yml").isNotNull();
        assertThat(Duration.parse(lease)).isGreaterThanOrEqualTo(Duration.ofSeconds(10));
    }
}
