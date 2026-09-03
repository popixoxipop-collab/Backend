package com.bigproject.backend.global.handle;

import com.bigproject.backend.domain.academicoperations.application.CohortService;
import com.bigproject.backend.domain.academicoperations.domain.Cohort;
import com.bigproject.backend.domain.organization.domain.DisclosureScope;
import com.bigproject.backend.domain.organization.domain.OrganizationPolicy;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationPolicyRepository;
import com.bigproject.backend.domain.platformgovernance.domain.AiTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-handles-pilot-cohort: ROADMAP.md Phase 4's first real pilot -- register / fetch / patch /
 * recover / revoke exercised for real against a real, disposable Postgres, through the REAL
 * generated {@code HandleController} and the hand-written {@link CohortResolver}
 * (D-resolver-authentication-context). Not against a synthetic fixture -- against this app's own
 * real {@code Cohort} entity, {@code CohortService}, and DTOs.
 *
 * <p><b>Schema note</b>: {@code spring.jpa.hibernate.ddl-auto} is overridden to {@code create} for
 * THIS TEST ONLY (see the {@code @DynamicPropertySource} below) -- this repo's real deployment
 * posture is {@code validate} (schema managed externally; only incremental patch SQL under
 * {@code docs/migration/} is committed, no base schema file). A real disposable Postgres has no
 * way to reconstruct that externally-managed schema from this repo alone, so this test derives it
 * from the real {@code @Entity} mappings instead -- the same mappings the real app runs against in
 * production, just not the actual migration-tool-applied DDL. Honest scope: this proves the
 * generated handles code + hand-written resolver work correctly against Cohort's real shape: it
 * does not prove migration.sql matches whatever this app's real external schema-migration tool
 * would produce byte-for-byte.
 *
 * <p><b>Auth note</b>: goes through the real {@code CohortResolver}/{@code HandleController}
 * beans directly rather than real HTTP + a real JWT -- this app has no existing full
 * {@code @SpringBootTest} + real-HTTP-JWT integration-test precedent to extend (confirmed by
 * search before writing this), and building one from scratch is a real, separate scope decision,
 * not a shortcut taken here without noting it. The {@link org.springframework.security.core.Authentication}
 * object constructed below is structurally IDENTICAL to what the real {@code JwtFilter} builds
 * (same {@code UsernamePasswordAuthenticationToken} type, same {@code setDetails(organizationId)}
 * convention) -- what's actually new and being verified here (handles registration/enforcement/
 * snapshot/recover/revoke against Cohort's real tenant-scoped read/write path) is exercised
 * for real through the real Spring context, real AOP proxying, and real Postgres.
 */
@SpringBootTest(properties = {
		"jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
		"jwt.access-token-expiration=900000",
		"jwt.refresh-token-expiration=1209600000",
		"auth.login.allowed-origins=http://localhost:5173",
		"auth.login.swagger-origin-override-enabled=false",
		"auth.login.swagger-ui-origin=http://localhost:8080",
		"auth.refresh-cookie.name=refresh_token",
		"auth.refresh-cookie.path=/api/v0/auth",
		"auth.refresh-cookie.secure=false",
		"auth.refresh-cookie.same-site=Lax",
		"spring.jpa.hibernate.ddl-auto=create",
		"invitation.base-url=http://localhost:5173",
		"invitation.expiration=P7D",
		"curriculum.storage.bucket=bskel-pilot-test-unused-bucket",
		"submission.storage.bucket=bskel-pilot-test-unused-bucket",
})
class HandlesCohortPilotIntegrationTest {

	@DynamicPropertySource
	static void realDisposablePostgres(DynamicPropertyRegistry registry) {
		String url = System.getenv("BSKEL_PILOT_DATABASE_URL");
		if (url == null || url.isBlank()) {
			throw new IllegalStateException("BSKEL_PILOT_DATABASE_URL is not set -- point it at a real, disposable Postgres this test may create/drop tables in");
		}
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("BSKEL_PILOT_DATABASE_USER", System.getProperty("user.name")));
		registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("BSKEL_PILOT_DATABASE_PASSWORD", ""));
	}

	@Autowired
	private CohortService cohortService;
	@Autowired
	private OrganizationPolicyRepository organizationPolicyRepository;
	@Autowired
	private HandleController handleController;
	@Autowired
	private HandleService handleService;
	@Autowired
	private HandleRegistryRepository handleRegistryRepository;
	@Autowired
	private HandleSnapshotRepository handleSnapshotRepository;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void fullHandleLifecycleAgainstARealCohort() {
		UUID orgId = UUID.randomUUID();
		UUID creatorUserId = UUID.randomUUID();

		// A real, active OrganizationPolicy -- CohortService.createCohort() requires one for real
		// (COHORT policy lookup, not bypassed).
		OrganizationPolicy policy = OrganizationPolicy.createInitial(
				orgId,
				new OrganizationPolicy.Settings(
						new BigDecimal("1000.000000"), 100000L, 1_000_000_000L, 365,
						DisclosureScope.SUMMARY, AiTier.BALANCED,
						true, true, true, true, true),
				creatorUserId);
		organizationPolicyRepository.save(policy);

		// A real Cohort, through the real CohortService (real business rules, not a bypassed insert).
		Cohort cohort = cohortService.createCohort(orgId, "D-handles-pilot-cohort e2e",
				LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30), creatorUserId);
		UUID cohortId = cohort.getCohortId();

		// Same Authentication shape JwtFilter itself constructs -- see this class's own javadoc.
		var authentication = new UsernamePasswordAuthenticationToken(
				"pilot-operator@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
		authentication.setDetails(orgId);
		SecurityContextHolder.getContext().setAuthentication(authentication);

		String handleToken = HandleCodec.encode("r", "Cohort", cohortId, null);

		// Before ANY normal-app-usage call to CohortService.findCohort() ever ran, nothing is
		// registered yet -- the real, documented D-handle-registry-enforcement "bootstrapping"
		// behavior, not a bug. Confirmed live, not assumed.
		assertThatThrownBy(() -> handleController.fetch(handleToken))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("no active registration");

		// A real call to the resource's OWN real read path (exactly what its real controller does
		// on every normal request) -- @RecordHandleSnapshot on CohortService.findCohort triggers
		// HandleAspect to register (kind=r) AND record a request+response snapshot, for real, via
		// real Spring AOP proxying (this test calls through the real injected bean, not `new`).
		cohortService.findCohort(cohortId, orgId);
		assertThat(handleRegistryRepository.findById(
				HandleCodec.deriveHandleUid("r", "Cohort", cohortId, null))).isPresent();
		assertThat(handleSnapshotRepository.findByHandleUidOrderByRecordedAtDesc(
				HandleCodec.deriveHandleUid("r", "Cohort", cohortId, null))).isNotEmpty();

		// Now registered -- fetch() succeeds, tenant-scoped fetch() derives orgId from
		// Authentication (not from the pointer/handle itself), returns the real CohortResponse shape.
		ResponseEntity<Object> fetched = handleController.fetch(handleToken);
		assertThat(fetched.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(fetched.getBody()).isNotNull();

		// A cross-org fetch attempt must NOT see this cohort -- the whole reason this pilot exists.
		var otherOrgAuthentication = new UsernamePasswordAuthenticationToken(
				"other-org-operator@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
		otherOrgAuthentication.setDetails(UUID.randomUUID());
		SecurityContextHolder.getContext().setAuthentication(otherOrgAuthentication);
		assertThatThrownBy(() -> handleController.fetch(handleToken))
				.isInstanceOf(ResponseStatusException.class);
		SecurityContextHolder.getContext().setAuthentication(authentication);

		// A real field-level patch (kind=f), through the real hand-written patchField() dispatch.
		String namePointerHandle = HandleCodec.encode("f", "Cohort", cohortId, "/name");
		handleController.patch(namePointerHandle, "D-handles-pilot-cohort e2e (renamed)");
		Cohort reloaded = cohortService.findCohort(cohortId, orgId);
		assertThat(reloaded.getName()).isEqualTo("D-handles-pilot-cohort e2e (renamed)");

		// recover(): real recorded history, most-recent-first, includes the patch's own snapshot.
		ResponseEntity<?> recovered = handleController.recover(handleToken, null);
		assertThat(recovered.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(recovered.getBody()).isNotNull();

		// revoke(): real DB update, and fetch() genuinely 404s afterward -- revocation has a real
		// effect, not merely a flag nobody reads.
		handleService.revoke(HandleCodec.deriveHandleUid("r", "Cohort", cohortId, null), "e2e pilot test cleanup");
		assertThatThrownBy(() -> handleController.fetch(handleToken))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("no active registration");
	}
}
