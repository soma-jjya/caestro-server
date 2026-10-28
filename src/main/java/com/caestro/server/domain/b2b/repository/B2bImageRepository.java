package com.caestro.server.domain.b2b.repository;

import com.caestro.server.domain.b2b.entity.B2bImage;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface B2bImageRepository extends JpaRepository<B2bImage, String> {
    // 현재 프레임과 세션 스냅샷 양쪽에서 해제된 이미지만. 바이트는 저장소에 따로 있어 id 로 하나씩 지운다(#186)
    @Query("select i.id from B2bImage i where i.createdAt < :before"
            + " and not exists (select f from B2bFrame f where f.image = i)"
            + " and not exists (select s from B2bFill s where s.image = i)")
    List<String> findUnreferencedIdsBefore(@Param("before") LocalDateTime before);

    @Modifying
    @Query("delete from B2bImage i where i.id = :id"
            + " and not exists (select f from B2bFrame f where f.image = i)"
            + " and not exists (select s from B2bFill s where s.image = i)")
    int deleteIfUnreferenced(@Param("id") String id);
}
