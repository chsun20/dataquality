package com.ruoyi.rule.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController extends BaseController{

    @RequestMapping("/test")
    public String test()
    {
        return "rule模块";
    }

}
