package ro.uvt.pokedex.core.view;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/test/mvc")
public class TestMvcThrowingController {

    @GetMapping("/illegal")
    public String illegal() {
        throw new IllegalArgumentException("bad input");
    }

    @GetMapping("/runtime")
    public String runtime() {
        throw new RuntimeException("boom");
    }
}
