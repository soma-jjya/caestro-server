package com.caestro.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * logback-spring.xml 회귀 가드 (#131). prod 프로파일의 JSON 어펜더는 dev·CI가 한 번도
 * 실행하지 않는 조건부 설정이라, 클래스명 오타·의존성 누락이 운영 기동 실패로 처음 발현된다
 * (#111 TargetLifecycleWatcher 사고와 같은 사각지대 구조) — 선언된 클래스가 클래스패스에
 * 실재하는지를 CI에서 강제한다.
 */
class LogbackConfigTest {

    @Test
    @DisplayName("logback-spring.xml이 선언한 모든 appender/encoder 클래스는 클래스패스에 존재한다")
    void allDeclaredClasses_existOnClasspath() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document doc;
        try (InputStream xml = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertThat(xml).as("logback-spring.xml이 리소스에 있어야 한다").isNotNull();
            doc = factory.newDocumentBuilder().parse(xml); // 문법 오류면 여기서 실패
        }

        List<String> declared = new ArrayList<>();
        collectClassAttributes(doc.getDocumentElement(), declared);
        assertThat(declared).as("검사할 class 선언이 최소 2개(appender+encoder)는 있어야 한다")
                .hasSizeGreaterThanOrEqualTo(2);

        for (String className : declared) {
            // 오타·의존성 누락이면 ClassNotFoundException → 테스트 실패 (운영 기동 실패의 CI 선행 발현)
            Class.forName(className);
        }
    }

    private void collectClassAttributes(Element element, List<String> out) {
        if (element.hasAttribute("class")) {
            out.add(element.getAttribute("class"));
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                collectClassAttributes(child, out);
            }
        }
    }
}
