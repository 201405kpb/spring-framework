package com.kpb.spring.web;

import java.util.Map;

import com.kpb.spring.service.HelloWorldService;
import com.kpb.spring.tx.Book;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class WelcomeController {

	private final Logger logger = LoggerFactory.getLogger(WelcomeController.class);
	private final HelloWorldService helloWorldService;

	@Autowired
	public WelcomeController(HelloWorldService helloWorldService) {
		this.helloWorldService = helloWorldService;
	}

	@RequestMapping(value = "/", method = RequestMethod.GET)
	public String index(Map<String, Object> model, HttpServletRequest request) {

		logger.debug("index() is executed!");
		model.put("title", helloWorldService.getTitle(""));
		model.put("msg", helloWorldService.getDesc());
		model.put("request",request);
		return "index";
	}

	@RequestMapping(value = "/hello/{name:.+}", method = RequestMethod.GET)
	public ModelAndView hello(@PathVariable("name") String name,HttpServletRequest request) {

		logger.debug("hello() is executed - $name {}", name);
		ModelAndView model = new ModelAndView();
		model.setViewName("index");
		model.addObject("title", helloWorldService.getTitle(name));
		model.addObject("msg", helloWorldService.getDesc());
		model.addObject("request",request);
		return model;

	}

	@RequestMapping(value = "book")
	@ResponseBody
	public Book BuildBook(){
		return new Book("Java EE 6", "Spring Boot 2.0", 99.0);
	}

}