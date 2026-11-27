package com.homestay.pms_backend.service;

import java.util.UUID;

public sealed interface ClaimOutcome permits ClaimOutcome.Claimed, ClaimOutcome.Replay {
    record Claimed(UUID claimId) implements ClaimOutcome {}
    record Replay(UUID resourceId) implements ClaimOutcome {}
}
