package com.gs5.kycaml.shared.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ActorRoleTest {

    @Test
    @DisplayName("the roles that may decide are exactly the two approving roles, confirmed by the product owner (BR-02, BR-33)")
    void shouldPinTheDecidingRolesToExactlyUnderwriterAndSeniorUnderwriter() {
        assertThat(Arrays.stream(ActorRole.values()).filter(ActorRole::canDecide))
                .containsExactlyInAnyOrder(ActorRole.UNDERWRITER, ActorRole.SENIOR_UNDERWRITER);
    }
}
