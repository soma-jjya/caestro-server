package com.caestro.server.domain.b2b.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 카탈로그 업로드에서 가져온 불변 사본의 메타데이터. 바이트는 CatalogAssetStore 에 같은 id 로 있다(#186). B2C 휴지통·정리 배치와 수명을 공유하지 않는다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "b2b_images")
public class B2bImage {
    @Id @Column(length = 40)
    private String id;
    // 입력 imageId를 다시 저장할 때 사본을 재사용하기 위한 값이며 catalog_images 외래키가 아니다.
    @Column(nullable = false, length = 32)
    private String sourceImageId;
    @Column(nullable = false, length = 40)
    private String contentType;
    @Column(nullable = false)
    private int width;
    @Column(nullable = false)
    private int height;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
