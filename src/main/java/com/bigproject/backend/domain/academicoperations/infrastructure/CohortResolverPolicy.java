package com.bigproject.backend.domain.academicoperations.infrastructure;

import java.util.UUID;

/**
 * Companion to {@link CohortResolver}, mirroring backend-skeleton's own generated
 * {@code <Type>ResolverPolicy} shape (D-resolver-policy-split) even though this pair was
 * hand-written rather than auto-generated (D-security-8 correctly refused to generate a resolver
 * for {@code Cohort} -- see {@link CohortResolver}'s own javadoc). Kept as a separate class for
 * the same reason the generated version is: {@code CONTRACT_REF}/{@code FEATURE_UID} are safe to
 * regenerate whenever the feature contract changes, without disturbing {@link CohortResolver}'s
 * own hand-written {@code fetch}/{@code patchField} bodies.
 *
 * <p>{@code CONTRACT_REF} is the real sha256 of
 * {@code specs/001-handles-pilot-cohort/contracts/001-handles-pilot-cohort.schema.json} as emitted
 * by {@code bskel contract emit}; {@code FEATURE_UID} is the real {@code feature_uid} from
 * {@code specs/001-handles-pilot-cohort/feature.json}. Both computed the same way
 * {@code emit.mjs}'s own {@code contractRefFor}/{@code featureUidFor} do, not invented.
 */
final class CohortResolverPolicy {

	private static final String CONTRACT_REF = "3f52b6e441cda806d877f87ea35ff6417c5effee89b3105ec5e0a25bafd364ef";
	private static final UUID FEATURE_UID = UUID.fromString("6bcbb17e-72fe-4049-92b5-712125c5c1ec");

	private CohortResolverPolicy() {}

	static String type() {
		return "Cohort";
	}

	// D-handles-pilot-cohort: CohortController.findCohort has NO @PreAuthorize at all -- it is
	// protected by JWT-derived organization scoping instead (extractOrganizationId), not a role.
	// TODO_ROLE (this project's usual fail-closed default for "no real value found") was tried
	// first and rejected on evidence: it makes handle-mediated fetch/recover permanently 403 for
	// EVERY caller, since no real role ever equals the literal string "TODO_ROLE" -- correct but
	// inert, not merely cautious. ROLE_OPERATOR is a DELIBERATE, STRICTER-THAN-THE-REAL-ENDPOINT
	// choice instead (the real GET allows any authenticated user in the caller's own org; this
	// requires the OPERATOR role on top of that) -- erring stricter is the safe direction, and
	// matches requiredAuthorityForPatch()'s own real, confirmed role. Flagged explicitly for the
	// pilot's eventual reviewer to confirm or loosen -- see DECISIONS.md D-handles-pilot-cohort.
	static String requiredAuthority() {
		return "ROLE_OPERATOR";
	}

	// Real, confirmed: @PreAuthorize("hasRole('OPERATOR')") on CohortController.updateCohort.
	static String requiredAuthorityForPatch() {
		return "ROLE_OPERATOR";
	}

	static String contractRef() {
		return CONTRACT_REF;
	}

	static UUID featureUid() {
		return FEATURE_UID;
	}
}
