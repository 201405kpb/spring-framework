package com.kpb.spring.annotation;

import com.kpb.spring.service.HelloWorldService;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.stereotype.Service;

import java.util.Set;

public class AnnotationTest {
	 private static org.slf4j.Logger logger = LoggerFactory.getLogger(AnnotationTest.class);
	public static void main(String[] args) {

		AnnotationAttributes types = AnnotatedElementUtils.getMergedAnnotationAttributes(HelloWorldService.class,Service.class);

		logger.info("types",types);


	}

}
