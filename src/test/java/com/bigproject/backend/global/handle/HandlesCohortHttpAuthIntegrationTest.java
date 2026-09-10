package com.bigproject.backend.global.handle;

import com.bigproject.backend.domain.academicoperations.application.CohortService;
import com.bigproject.backend.domain.academicoperations.domain.Cohort;
import com.bigproject.backend.domain.academicoperations.infrastructure.CohortResolver;
import com.bigproject.backend.domain.organization.domain.DisclosureScope;
import com.bigproject.backend.domain.organization.domain.OrganizationPolicy;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationPolicyRepository;
import com.bigproject.backend.domain.platformgovernance.domain.AiTier;
import com.bigproject.backend.global.security.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3-2 (ROADMAP.md Phase 4 / W3 plan G1): closes {@link HandlesCohortPilotIntegrationTest}'s own
 * documented scope gap -- that test goes through the real {@code CohortResolver}/{@code
 * HandleController} beans directly with a hand-constructed {@code Authentication}, not real HTTP
 * + a real JWT. This class does: {@code @AutoConfigureMockMvc} with the default web environment
 * runs requests through the REAL {@code SecurityFilterChain} and the REAL {@link
 * com.bigproject.backend.global.security.JwtFilter} (same precedent import path as {@code
 * LoginOriginPreflightTest}, this repo's only other {@code @AutoConfigureMockMvc} test on this
 * Spring Boot 4.1 line -- {@code MockMvcRequestBuilders}/{@code MockMvcResultMatchers} stay on
 * their classic package, confirmed by cross-referencing {@code MemberQueryControllerTest}). JWTs
 * are minted for real via the real {@link JwtProvider} bean, not hand-built.
 *
 * <p>A new file, not an edit of {@code HandlesCohortPilotIntegrationTest} -- that class's own
 * green evidence (988/988, signed attestation) stays independently valid regardless of anything
 * this class finds.
 *
 * <p><b>Schema/auth setup notes</b>: see {@code HandlesCohortPilotIntegrationTest}'s own javadoc
 * for why {@code ddl-auto=create} and a hand-rolled minimal {@code app_user} table are used here
 * too -- identical reasoning, not repeated.
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
@AutoConfigureMockMvc
class HandlesCohortHttpAuthIntegrationTest {

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

	// W3-3: optional, unlike the DB URL above -- unset means ContractObservationAspect's own
	// @Value("${bskel.observe.signing-key-pem:}") falls back to its empty-string default and every
	// receipt stays unsigned, exactly the backward-compatible behavior D-runtime-conformance-
	// receipts' cryptographic-attestation Update note documents. Wired the same way the DB URL
	// above already is -- a human decides where the real secret value comes from (here: an env var
	// this W3-3 pilot run set from a real `bskel attest keygen` output), bskel never chooses it.
	@DynamicPropertySource
	static void observeSigningKey(DynamicPropertyRegistry registry) {
		String pem = System.getenv("BSKEL_OBSERVE_SIGNING_KEY_PEM");
		if (pem != null && !pem.isBlank()) {
			registry.add("bskel.observe.signing-key-pem", () -> pem);
		}
	}

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private CohortService cohortService;
	@Autowired
	private OrganizationPolicyRepository organizationPolicyRepository;
	@Autowired
	private JwtProvider jwtProvider;
	@Autowired
	private HandleService handleService;
	@Autowired
	private CohortResolver cohortResolver;
	@Autowired
	private DataSource dataSource;

	/** Same minimal, test-only DDL as {@code HandlesCohortPilotIntegrationTest}, parameterized by role/org so this test can mint three distinct real principals. */
	private void createUser(UUID userId, UUID orgId, String email, String roleCode) throws Exception {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE IF NOT EXISTS app_user (
						user_id UUID PRIMARY KEY,
						org_id UUID NOT NULL,
						email TEXT NOT NULL,
						normalized_email TEXT NOT NULL,
						name TEXT NOT NULL,
						password_hash TEXT NOT NULL,
						status TEXT NOT NULL,
						is_email_verified BOOLEAN NOT NULL,
						login_blocked_until TIMESTAMPTZ,
						password_changed_at TIMESTAMPTZ,
						role_code TEXT NOT NULL,
						deleted_at TIMESTAMPTZ
					)
					""");
			statement.execute("""
					INSERT INTO app_user (user_id, org_id, email, normalized_email, name, password_hash, status, is_email_verified, role_code)
					VALUES ('%s', '%s', '%s', '%s', 'HTTP Pilot User', 'unused', 'ACTIVE', true, '%s')
					ON CONFLICT DO NOTHING
					""".formatted(userId, orgId, email, email, roleCode));
		}
	}

	@Test
	void realHttpAndRealJwtThroughTheRealSecurityFilterChain() throws Exception {
		UUID orgId = UUID.randomUUID();
		UUID outOfOrgId = UUID.randomUUID();
		UUID creatorUserId = UUID.randomUUID();
		createUser(creatorUserId, orgId, "http-pilot-operator@example.com", "OPERATOR");
		createUser(UUID.randomUUID(), orgId, "http-pilot-manager@example.com", "MANAGER");
		createUser(UUID.randomUUID(), outOfOrgId, "http-pilot-outsider@example.com", "OPERATOR");

		OrganizationPolicy policy = OrganizationPolicy.createInitial(
				orgId,
				new OrganizationPolicy.Settings(
						new BigDecimal("1000.000000"), 100000L, 1_000_000_000L, 365,
						DisclosureScope.SUMMARY, AiTier.BALANCED,
						true, true, true, true, true),
				creatorUserId);
		organizationPolicyRepository.save(policy);

		Cohort cohort = cohortService.createCohort(orgId, "D-handles-pilot-cohort http e2e",
				LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30), creatorUserId);
		UUID cohortId = cohort.getCohortId();
		String handle = HandleCodec.encode("r", "Cohort", cohortId, null);
		String namePointerHandle = HandleCodec.encode("f", "Cohort", cohortId, "/name");

		String operatorToken = jwtProvider.createAccessToken("http-pilot-operator@example.com", "OPERATOR", orgId);
		String managerToken = jwtProvider.createAccessToken("http-pilot-manager@example.com", "MANAGER", orgId);
		String outsiderToken = jwtProvider.createAccessToken("http-pilot-outsider@example.com", "OPERATOR", outOfOrgId);

		// No Authorization header -- the real AuthenticationEntryPoint fires before the controller,
		// so this 401s the same whether or not the handle has ever been registered.
		mockMvc.perform(get("/handles/{handle}", handle))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		// Bootstrap registration exactly like HandlesCohortPilotIntegrationTest does: HandleController
		// checks requireRegisteredOrThrow() BEFORE ever calling resolver.fetch() (the only thing that
		// can register), so the handle-mediated path alone can never self-bootstrap -- confirmed live
		// here too (this exact priming call was missing on the first attempt at this test, and
		// reproduced that class's own documented 404-on-first-call precondition instead of a bskel
		// bug). In real production this priming call is whatever real, non-handle endpoint the app
		// itself exposes for this resource (e.g. CohortController's own GET) -- simulated directly here.
		cohortService.findCohort(cohortId, orgId);

		// First real HTTP call against the now-registered handle: records a SECOND real snapshot via
		// the real HandleAspect, through real HTTP + the real JwtFilter (not a hand-built Authentication).
		mockMvc.perform(get("/handles/{handle}", handle).header("Authorization", "Bearer " + operatorToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cohortId").value(cohortId.toString()))
				.andExpect(jsonPath("$.name").value("D-handles-pilot-cohort http e2e"));

		// In-org but not OPERATOR -- CohortResolverPolicy's real, deliberately-stricter-than-the-
		// real-endpoint ROLE_OPERATOR requirement (see that class's own javadoc).
		mockMvc.perform(get("/handles/{handle}", handle).header("Authorization", "Bearer " + managerToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("requires authority ROLE_OPERATOR"));

		// Out-of-org OPERATOR -- tenant isolation must hold over real HTTP too: CohortService's own
		// (cohortId, orgId) filter throws the real ApiException(COHORT_NOT_FOUND).
		mockMvc.perform(get("/handles/{handle}", handle).header("Authorization", "Bearer " + outsiderToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COHORT_NOT_FOUND"));

		// PATCH a resource-level (kind=r) handle -- rejected before any resolver dispatch.
		mockMvc.perform(patch("/handles/{handle}", handle)
						.header("Authorization", "Bearer " + operatorToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("\"ignored\""))
				.andExpect(status().isBadRequest());

		// recover() before any snapshot: HandleAspect's @Around advice always registers AND
		// records a snapshot together (both wrap the SAME annotated read-path call), so
		// "registered but zero snapshots" cannot be reached through normal application usage.
		// Constructing it explicitly via HandleService.register() directly (bypassing the aspect)
		// is the only way to exercise this branch of HandleController.recover() -- the same
		// direct-HandleService convention HandlesCohortPilotIntegrationTest already uses for its
		// own revoke() cleanup call, not a shortcut invented here.
		Cohort secondCohort = cohortService.createCohort(orgId, "D-handles-pilot-cohort http e2e (no snapshot)",
				LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30), creatorUserId);
		UUID secondCohortId = secondCohort.getCohortId();
		handleService.register("r", "Cohort", secondCohortId, null,
				cohortResolver.featureUid(), "findCohort", cohortResolver.contractRef());
		String secondHandle = HandleCodec.encode("r", "Cohort", secondCohortId, null);
		mockMvc.perform(get("/handles/{handle}/recover", secondHandle).header("Authorization", "Bearer " + operatorToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("no snapshot recorded for this handle"));

		// recover() after a real snapshot -- the primary cohort has exactly one, from the very
		// first GET above.
		mockMvc.perform(get("/handles/{handle}/recover", handle).header("Authorization", "Bearer " + operatorToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operation_id").value("findCohort"))
				.andExpect(jsonPath("$.schema_drift").value(false));

		// A real field-level PATCH over real HTTP -- exercises requiredAuthorityForPatch(),
		// patchField()'s per-field dispatch, and CurrentUserResolver's own app_user lookup
		// together, not just the read side already covered above.
		mockMvc.perform(patch("/handles/{handle}", namePointerHandle)
						.header("Authorization", "Bearer " + operatorToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("\"D-handles-pilot-cohort http e2e (renamed)\""))
				.andExpect(status().isNoContent());
		Cohort reloaded = cohortService.findCohort(cohortId, orgId);
		assertThat(reloaded.getName()).isEqualTo("D-handles-pilot-cohort http e2e (renamed)");

		// revoke() has no HTTP endpoint (D-handle-lifecycle's documented scope) -- called directly
		// via the same HandleService the non-HTTP pilot test uses for the same reason.
		handleService.revoke(HandleCodec.deriveHandleUid("r", "Cohort", cohortId, null), "http e2e pilot test cleanup");

		// After revoke(): GET real-404s over real HTTP.
		mockMvc.perform(get("/handles/{handle}", handle).header("Authorization", "Bearer " + operatorToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("no active registration for this handle"));

		// After revoke(): PATCH real-404s over real HTTP too, same message -- the two enforcement
		// call sites can never silently drift apart (see HandleController's own
		// requireRegisteredOrThrow javadoc).
		mockMvc.perform(patch("/handles/{handle}", namePointerHandle)
						.header("Authorization", "Bearer " + operatorToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("\"ignored\""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("no active registration for this handle"));
	}
}
