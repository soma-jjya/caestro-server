package com.caestro.server.domain.deveval.repository;

import com.caestro.server.domain.deveval.entity.DevEvaluationPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DevEvaluationPhotoRepository extends JpaRepository<DevEvaluationPhoto, String> {

    /** 사진 행을 읽지 않고 지운다(옛 사진 바꾸기·기록 지우기). 엔티티로 지우면 블롭(최대 8MB)을 먼저 읽어 온다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from DevEvaluationPhoto p where p.id = :id")
    int deleteQuietly(@Param("id") String id);
}
