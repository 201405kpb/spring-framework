package com.kpb.spring.annotation;

import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.web.bind.annotation.RequestMapping;

public class AnnotationTest {
	private static final String TAG = "AnnotationTest";
	 private static org.slf4j.Logger logger = LoggerFactory.getLogger(AnnotationTest.class);
	public static void main(String[] args) {

		AnnotationAttributes bean =
				AnnotatedElementUtils.findMergedAnnotationAttributes(ChildController.class, RequestMapping.class, false, false);
		MergedAnnotations mergedAnnotations = MergedAnnotations.from(ChildController.class);
		boolean present = mergedAnnotations.isPresent(RequestMapping.class.getName());
		boolean directlyPresent = mergedAnnotations.isDirectlyPresent(RequestMapping.class);
		logger.info(String.valueOf(directlyPresent));

	}

	@RequestMapping(value = "parent/controller")
	class ParentController {
	}

	@RequestMapping(value = "child/controller")
	class ChildController extends ParentController {
	}
}
