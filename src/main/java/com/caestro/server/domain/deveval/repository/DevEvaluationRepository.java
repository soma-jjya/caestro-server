package com.caestro.server.domain.deveval.repository;

import com.caestro.server.domain.deveval.entity.DevEvaluation;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DevEvaluationRepository extends JpaRepository<DevEvaluation, String> {

    long countByUserId(Long userId);

    /** 관리자 목록·CSV. 조건은 null 이면 빠진다. 정렬은 호출자가 준다(최근 고친 것부터). */
    @Query("select e from DevEvaluation e where (:guide is null or e.guide = :guide) and (:userId is null or e.userId = :userId)")
    Page<DevEvaluation> search(@Param("guide") Boolean guide, @Param("userId") Long userId, Pageable pageable);

    @Query("select e from DevEvaluation e where (:guide is null or e.guide = :guide) and (:userId is null or e.userId = :userId)")
    List<DevEvaluation> searchAll(@Param("guide") Boolean guide, @Param("userId") Long userId, Sort sort);

    /** 요약 숫자를 테이블 한 번 통과로. sum 은 행이 없으면 null 이라 coalesce 로 0. */
    @Query("""
            select new com.caestro.server.domain.deveval.repository.DevEvaluationCounts(
                count(e),
                count(distinct e.userId),
                coalesce(sum(case when e.photoBytes is not null then 1L else 0L end), 0L),
                coalesce(sum(case when e.guide = true then 1L else 0L end), 0L),
                avg(case when e.guide = true then cast(e.rating as Double) / e.ratingMax end),
                coalesce(sum(case when e.guide = false then 1L else 0L end), 0L),
                avg(case when e.guide = false then cast(e.rating as Double) / e.ratingMax end))
            from DevEvaluation e
            """)
    DevEvaluationCounts counts();

    /** 만점 값을 많이 쓰인 순으로(같으면 큰 값). 첫 번째가 요약의 ratingMax. */
    @Query("select e.ratingMax from DevEvaluation e group by e.ratingMax order by count(e) desc, e.ratingMax desc")
    List<Integer> ratingMaxByUse(Pageable pageable);
}
