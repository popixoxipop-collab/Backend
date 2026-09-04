package com.bigproject.backend.domain.academicoperations.application;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.organization.domain.OrganizationErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.domain.academicoperations.domain.Cohort;
import com.bigproject.backend.domain.academicoperations.domain.CohortStatus;
import com.bigproject.backend.domain.academicoperations.infrastructure.CohortRepository;
import com.bigproject.backend.global.handle.RecordHandleSnapshot;
import com.bigproject.backend.domain.organization.domain.OrganizationPolicy;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CohortService {

    private final CohortRepository cohortRepository;
    private final OrganizationPolicyRepository organizationPolicyRepository;
    private final ClassroomService classroomService;
    private final com.bigproject.backend.domain.academicoperations.infrastructure.CohortMemberRepository cohortMemberRepository;
    private final com.bigproject.backend.domain.academicoperations.infrastructure.ClassMembershipRepository classMembershipRepository;
    private final com.bigproject.backend.domain.academicoperations.infrastructure.ClassroomRepository classroomRepository;
    private final com.bigproject.backend.domain.academicoperations.domain.CohortDependencyRepository cohortDependencyRepository;
    private final com.bigproject.backend.domain.academicoperations.infrastructure.ManagerAssignmentRepository managerAssignmentRepository;
    // 기수 생성
    @Transactional
    public Cohort createCohort(UUID orgId, String name, LocalDate startDate,
                               LocalDate endDate, UUID creatorUserId) {

        // 규칙 1: 기관 내 기수명 중복 금지
        if (cohortRepository.existsByOrgIdAndNameAndDeletedAtIsNull(orgId, name)) {
            throw new ApiException(AcademicOperationsErrorCode.COHORT_NAME_TAKEN, "이미 존재하는 기수명입니다: " + name);
        }

        // 규칙 2: 엔티티 생성자가 던지기 전에 먼저 걸러서 400으로 응답 (IllegalArgumentException은 전역에서 안 잡힘)
        if (startDate.isAfter(endDate)) {
            throw new ApiException(AcademicOperationsErrorCode.COHORT_PERIOD_INVALID);
        }

        OrganizationPolicy policy = organizationPolicyRepository
                .findByOrgIdAndStatus(orgId, OrganizationPolicy.Status.ACTIVE)
                .orElseThrow(() -> new ApiException(
                        OrganizationErrorCode.ORG_POLICY_NOT_FOUND, "기관에 활성 운영 정책이 없어 기수를 만들 수 없습니다."));

        Cohort cohort = Cohort.builder()
                .orgId(orgId)
                .name(name)
                .startDate(startDate)
                .endDate(endDate)
                .createdBy(creatorUserId)
                .disclosureScope(policy.getDefaultDisclosureScope())
                .disclosurePolicyId(policy.getPolicyId())
                .build();

        return cohortRepository.save(cohort);
    }

    // 기수 단건 조회
    // D-handles-pilot-cohort: no fields need redaction here (org id, cohort name, dates, status --
    // no personal/trainee data) -- an explicit, considered "empty" decision, not an unconsidered
    // default. See DECISIONS.md for the full record.
    @RecordHandleSnapshot(resourceType = "Cohort", operationId = "findCohort", resourceUidParam = 0)
    public Cohort findCohort(UUID cohortId, UUID orgId) {
        return cohortRepository.findByCohortIdAndOrgIdAndDeletedAtIsNull(cohortId, orgId)
                .orElseThrow(() -> new ApiException(AcademicOperationsErrorCode.COHORT_NOT_FOUND));
    }

    // 기수 종료: 기수 상태 변경 + 소속 반 배정·매니저 배정 일괄 해제
    // retention_policy_id/retention_until은 종료 시점의 활성 기관 정책을 스냅샷으로 고정한다 (createCohort의
    // disclosure 스냅샷과 동일한 패턴). DB CHECK(ck_cohort_closed)가 CLOSED 전이 시 이 두 값을 필수로 요구한다.
    @Transactional
    public Cohort closeCohort(UUID cohortId, UUID orgId, UUID actorUserId) {
        Cohort cohort = findCohort(cohortId, orgId);
        OrganizationPolicy policy = organizationPolicyRepository
                .findByOrgIdAndStatus(orgId, OrganizationPolicy.Status.ACTIVE)
                .orElseThrow(() -> new ApiException(
                        OrganizationErrorCode.ORG_POLICY_NOT_FOUND, "기관에 활성 운영 정책이 없어 기수를 종료할 수 없습니다."));

        cohort.close(actorUserId, policy.getPolicyId(), policy.getRetentionDays());
        classroomService.releaseAllAssignmentsForCohort(cohortId, orgId, actorUserId);
        return cohort;
    }

    /**
     * 기수 이름·기간 수정(11차 Q2). <b>개강 전({@code PLANNED})에만</b> 열려 있다.
     *
     * <p>반 수정과 같이 부분 수정이라 보낸 필드만 바뀐다 — 이름만 고쳐도 기간이 덮이지 않는다.
     * 개강 후를 막는 이유는 기간이 이미 발행된 리포트·회차 일정의 기준이기 때문이다.
     */
    @Transactional
    @RecordHandleSnapshot(resourceType = "Cohort", operationId = "updateCohort", resourceUidParam = 0)
    public Cohort updateCohort(UUID cohortId, UUID orgId, String name,
                               LocalDate startDate, LocalDate endDate, UUID actorUserId) {
        if (name == null && startDate == null && endDate == null) {
            throw new ApiException(AcademicOperationsErrorCode.COHORT_UPDATE_EMPTY);
        }

        Cohort cohort = findCohort(cohortId, orgId);
        if (cohort.getStatus() != CohortStatus.PLANNED) {
            throw new ApiException(AcademicOperationsErrorCode.COHORT_NOT_MUTABLE,
                    "개강 전(PLANNED) 기수만 수정할 수 있습니다. 현재 상태: " + cohort.getStatus());
        }

        // 이름을 바꾸는 경우에만 중복을 본다 — 자기 이름을 그대로 다시 보내는 것이 409가 되면 안 된다.
        if (name != null && !name.equals(cohort.getName())
                && cohortRepository.existsByOrgIdAndNameAndDeletedAtIsNull(orgId, name)) {
            throw new ApiException(AcademicOperationsErrorCode.COHORT_NAME_TAKEN, "이미 존재하는 기수명입니다: " + name);
        }

        try {
            cohort.edit(name, startDate, endDate, actorUserId);
        } catch (IllegalArgumentException exception) {
            // 엔티티가 던지는 것은 전역에서 안 잡혀 500이 된다. 입력 오류이므로 400으로 바꿔 준다.
            throw new ApiException(AcademicOperationsErrorCode.COHORT_PERIOD_INVALID);
        }
        return cohort;
    }

    /**
     * 기수 삭제(11차 Q2). <b>개강 전이고 아무것도 붙지 않은 기수만</b> 지운다.
     *
     * <p>잘못 만든 기수를 되돌리기 위한 것이지 운영 중인 기수를 정리하는 수단이 아니다.
     * 명단·반·회차 중 하나라도 있으면 막고, 무엇이 걸렸는지 메시지에 담는다 — 화면이 할 일은
     * 어느 쪽이든 "지울 수 없습니다" 하나라 코드는 쪼개지 않는다(반 삭제와 같은 규칙).
     */
    @Transactional
    public void deleteCohort(UUID cohortId, UUID orgId, UUID actorUserId) {
        Cohort cohort = findCohort(cohortId, orgId);

        if (cohort.getStatus() != CohortStatus.PLANNED) {
            throw new ApiException(AcademicOperationsErrorCode.COHORT_NOT_DELETABLE,
                    "개강 전(PLANNED) 기수만 삭제할 수 있습니다. 현재 상태: " + cohort.getStatus());
        }

        java.util.List<String> blockers = new java.util.ArrayList<>();
        if (cohortDependencyRepository.hasMembers(cohortId)) {
            blockers.add("등록된 교육생");
        }
        if (cohortDependencyRepository.hasClassrooms(cohortId)) {
            blockers.add("만들어진 반");
        }
        if (cohortDependencyRepository.hasProjects(cohortId)) {
            blockers.add("만들어진 회차");
        }
        if (!blockers.isEmpty()) {
            throw new ApiException(AcademicOperationsErrorCode.COHORT_NOT_DELETABLE,
                    String.join("·", blockers) + "이(가) 있어 삭제할 수 없습니다. 먼저 정리해 주세요.");
        }

        cohort.softDelete(actorUserId);
    }

    // 기수 목록 조회
    public Page<Cohort> findCohorts(UUID orgId, CohortStatus status, String query, Pageable pageable) {
        return cohortRepository.findCohorts(orgId, status, query, pageable);
    }

    /**
     * 기수별 재적 교육생 수(10차 R3). 목록·단건이 같은 경로를 쓰도록 항상 Map으로 돌려준다.
     *
     * <p>구성원이 하나도 없는 기수는 GROUP BY 결과에 아예 나오지 않으므로,
     * 호출부는 <b>없는 키를 0으로</b> 읽어야 한다.
     */
    /**
     * 기수별 반 개수(13차 Q1). {@link #countActiveTrainees}와 같은 방식으로 한 번에 센다 —
     * 기수 탭의 `반` 열 하나 때문에 기수마다 반 목록을 부르지 않게 하기 위한 것이다.
     *
     * <p>반이 없는 기수는 GROUP BY 결과에 나오지 않으므로 호출부가 <b>없는 키를 0으로</b> 읽어야 한다.
     */
    public java.util.Map<UUID, Integer> countClassrooms(java.util.List<UUID> cohortIds, UUID orgId) {
        if (cohortIds.isEmpty()) {
            return java.util.Map.of();
        }
        return classroomRepository.countByCohortIdIn(cohortIds, orgId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.bigproject.backend.domain.academicoperations.infrastructure
                                .ClassroomRepository.CohortClassroomCount::getCohortId,
                        row -> Math.toIntExact(row.getCount())));
    }

    public java.util.Map<UUID, Integer> countActiveTrainees(java.util.List<UUID> cohortIds, UUID orgId) {
        if (cohortIds.isEmpty()) {
            return java.util.Map.of();
        }
        return cohortMemberRepository.countActiveByCohortIdIn(cohortIds, orgId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.bigproject.backend.domain.academicoperations.infrastructure
                                .CohortMemberRepository.CohortTraineeCount::getCohortId,
                        row -> Math.toIntExact(row.getCount())));
    }

    // GET /members/me/enrollments — 로그인한 교육생이 자기 소속 기수·반을 모른 채로 맨 처음 호출하는 진입점.
    // 기수당 반은 최대 1건이라 가정하고, 배정 전(className=null)도 정상 케이스로 내려준다.
    public java.util.List<EnrollmentView> findMyEnrollments(UUID userId, UUID orgId) {
        java.util.List<com.bigproject.backend.domain.academicoperations.domain.CohortMember> memberships =
                cohortMemberRepository.findByUserIdAndOrgIdAndLeftAtIsNull(userId, orgId);

        java.util.stream.Stream<EnrollmentView> traineeEnrollments = memberships.stream()
                .map(member -> {
                    Cohort cohort = findCohort(member.getCohortId(), orgId);
                    return classMembershipRepository
                            .findByCohortMemberIdAndOrgIdAndUnassignedAtIsNull(member.getCohortMemberId(), orgId)
                            .map(classMembership -> classroomRepository
                                    .findByClassIdAndOrgIdAndDeletedAtIsNull(classMembership.getClassId(), orgId)
                                    .map(classroom -> enrollmentView(cohort, classroom.getClassId(), classroom.getName()))
                                    .orElseGet(() -> enrollmentView(cohort, null, null)))
                            .orElseGet(() -> enrollmentView(cohort, null, null));
                });

        // 매니저는 cohort_member에 행이 없다 — 소속 원장이 manager_assignment이기 때문이다(교육생만
        // 초대를 수락해 cohort_member에 들어간다). 위 스트림만 쓰면 담당 기수가 있는 매니저도 항상 빈
        // 배열을 받는다. classroom은 문서화된 대로 always null로 내려준다 — 담당 반은
        // GET /cohorts/{cohortId}/classrooms가 별도로 돌려주는 값이다.
        java.util.stream.Stream<EnrollmentView> managerEnrollments = managerAssignmentRepository
                .findByManagerUserIdAndOrgIdAndStatusAndUnassignedAtIsNull(userId, orgId, "ACTIVE")
                .stream()
                .map(assignment -> classroomRepository
                        .findByClassIdAndOrgIdAndDeletedAtIsNull(assignment.getClassId(), orgId)
                        .map(classroom -> classroom.getCohortId()))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .distinct()
                .map(cohortId -> enrollmentView(findCohort(cohortId, orgId), null, null));

        return java.util.stream.Stream.concat(traineeEnrollments, managerEnrollments)
                .sorted(ENROLLMENT_ORDER)
                .toList();
    }

    private EnrollmentView enrollmentView(Cohort cohort, UUID classId, String className) {
        return new EnrollmentView(cohort.getCohortId(), cohort.getName(), cohort.getStatus(),
                cohort.getStartDate(), classId, className);
    }

    /**
     * 담당·소속 기수가 둘 이상일 때 <b>화면이 기본으로 열 기수</b>를 정하는 순서다(30차 R9).
     *
     * <p>종전에는 {@code cohort_member} 조회 순서 그대로였고 그 순서는 정의된 적이 없다. 진행 중인
     * 기수와 막 끝난 기수를 함께 맡은 매니저가 생기면 화면은 목록 첫 항목을 고를 수밖에 없는데,
     * 그 첫 항목이 무엇인지 아무도 약속하지 않은 상태였다.
     *
     * <p>진행 중을 먼저 놓고, 같은 상태 안에서는 최근 시작한 것을 먼저 놓는다 — 오퍼레이터 화면이
     * 「진행 중을 고른다」로 쓰는 규칙과 같다. 화면은 첫 원소를 그대로 열면 된다.
     */
    private static final Comparator<EnrollmentView> ENROLLMENT_ORDER = Comparator
            .comparingInt((EnrollmentView view) -> switch (view.status()) {
                case RUNNING -> 0;
                case PLANNED -> 1;
                case CLOSED -> 2;
            })
            .thenComparing(EnrollmentView::startDate, Comparator.reverseOrder())
            .thenComparing(EnrollmentView::cohortName);

    public record EnrollmentView(UUID cohortId, String cohortName, CohortStatus status,
                                 LocalDate startDate, UUID classId, String className) {
    }
}