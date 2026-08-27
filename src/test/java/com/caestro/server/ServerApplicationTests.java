package com.caestro.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// RANDOM_PORT: 모의 서블릿 환경에는 WS ServerContainer 속성이 없어 WS 컨테이너 설정 빈(#97)이
// 초기화될 수 없다 → 실제 내장 톰캣으로 부팅해 운영과 같은 배선을 검증한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
