package com.bigproject.backend.global.handle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * D-handles-pilot-cohort: {@link HandleService#pruneSnapshotsOlderThan} exists in every
 * registry-capable provider but is never auto-invoked by anything {@code bskel} generates
 * (CATALOG.md's own O4 status note). ROADMAP.md Phase 3's pilot checklist requires an explicit
 * retention decision, not silence -- this class is that decision: real customer data (request/
 * response envelopes) accumulates in {@code sbf_handle_snapshot} the moment
 * {@code @RecordHandleSnapshot} is applied to anything, so it must be pruned on SOME schedule
 * rather than growing unbounded.
 *
 * <p>Mirrors {@code ProjectLifecycleScheduler}'s own established shape in this codebase
 * (config-property-overridable {@code fixedDelay}/{@code initialDelay}, one try/catch so a single
 * failed run never kills the schedule, 0-deletions logged at debug rather than info since it's the
 * common case).
 *
 * <p><b>The 90-day default is this pilot's own placeholder, not a value derived from any real
 * Team-IZ retention policy</b> -- unlike {@code Cohort}'s own {@code retentionUntil}/
 * {@code retentionPolicyId} fields, which DO encode a real, org-configurable policy, nothing in
 * this codebase specifies how long a handle audit snapshot should live. Confirm/adjust
 * {@code handles.snapshot.retention.days} before this pilot is ever deployed for real.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandleSnapshotRetentionScheduler {

	private final HandleService handleService;

	@Scheduled(
			fixedDelayString = "${handles.snapshot.retention.scheduler.delay:PT24H}",
			initialDelayString = "${handles.snapshot.retention.scheduler.initial-delay:PT5M}")
	public void pruneOldSnapshots() {
		try {
			int retentionDays = 90;
			Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
			long deleted = handleService.pruneSnapshotsOlderThan(cutoff);
			if (deleted > 0) {
				log.info("pruned handle snapshots older than {} days: count={}", retentionDays, deleted);
			}
		} catch (RuntimeException exception) {
			log.error("handle snapshot pruning failed", exception);
		}
	}
}
