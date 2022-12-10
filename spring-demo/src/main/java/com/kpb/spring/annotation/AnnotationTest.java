package com.kpb.spring.annotation;

import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.MergedAnnotations;

public class AnnotationTest {
	private static final String TAG = "AnnotationTest";
	 private static org.slf4j.Logger logger = LoggerFactory.getLogger(AnnotationTest.class);
	public static void main(String[] args) {

		MergedAnnotations mergedAnnotations = MergedAnnotations.from(ChildController.class);
		boolean present = mergedAnnotations.isPresent(RequestMapping.class.getName());
		boolean directlyPresent = mergedAnnotations.isDirectlyPresent(RequestMapping.class);
		logger.info(String.valueOf(directlyPresent));

	}

	@RequestMapping(value = "parent/controller")
	class ParentController {
	}

	@PostMapping(value = "child/controller")
	class ChildController extends ParentController {
	}
}
