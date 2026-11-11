package com.caestro.server.domain.deveval.repository;

import com.caestro.server.domain.deveval.entity.DevEvaluationGuide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DevEvaluationGuideRepository extends JpaRepository<DevEvaluationGuide, String> {

    /** 블롭을 읽지 않고 지운다(바꾸기). */
    @Modifying(flushAutomatically = true)
    @Query("delete from DevEvaluationGuide g where g.id = :id")
    int deleteQuietly(@Param("id") String id);

    /** 기록의 가이드 이미지를 모두 지운다(기록 지우기). */
    @Modifying(flushAutomatically = true)
    @Query("delete from DevEvaluationGuide g where g.evaluationId = :evaluationId")
    int deleteByEvaluation(@Param("evaluationId") String evaluationId);
}
