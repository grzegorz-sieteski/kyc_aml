package com.gs5.kycaml.risk.adapters.persistence;

import com.gs5.kycaml.risk.adapters.RiskDatabaseSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-20 (stage 6, SR-13; D-05, BR-43): the technical identity of a record never leaves the service, and a
 * log shipped to a central platform is outside it. A stored profile that cannot be read back is still
 * reported, but by nothing that names it.
 */
@ExtendWith(OutputCaptureExtension.class)
class RiskProfileLogIT extends RiskDatabaseSupport {

    @Test
    @DisplayName("an unreadable stored profile is treated as not found and logged without its internal id")
    void shouldNotLogTheInternalIdOfAnUnreadableProfile(CapturedOutput output) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO command.risk_profile
                            (id, tenant_id, customer_number, evaluation, evaluated_at, updated_at, version)
                        VALUES (:id, :tenant, 'C-UNREADABLE', CAST('[]' AS JSONB), now(), now(), 1)
                        """)
                .param("id", id).param("tenant", DE_CENTRE).update();

        var found = profiles.findOwn(DE_CENTRE, number("C-UNREADABLE"));

        assertThat(found).isEmpty();
        assertThat(output.getAll()).as("the unreadable profile is still reported").contains("cannot be read back");
        assertThat(output.getAll()).doesNotContain(id.toString());
    }
}
