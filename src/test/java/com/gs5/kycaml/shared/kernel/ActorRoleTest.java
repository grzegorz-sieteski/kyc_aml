package com.gs5.kycaml.shared.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ActorRoleTest {

    @Test
    @DisplayName("the roles are exactly the five of assumption A-02, confirmed by the product owner")
    void shouldPinTheRolesToExactlyTheFiveOfAssumptionA02() {
        assertThat(Arrays.stream(ActorRole.values()).map(Enum::name))
                .containsExactlyInAnyOrder(
                        "ONBOARDING_SYSTEM", "AML_ANALYST_L1", "AML_ANALYST_L2", "MLRO", "FRONT_OFFICE");
    }
}
