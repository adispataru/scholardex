package ro.uvt.pokedex.core.controller;

//import javax.servlet.RequestDispatcher;
//import javax.servlet.http.HttpServletRequest;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class CustomErrorController implements ErrorController {

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);

        if (status != null) {
            int statusCode = Integer.parseInt(status.toString());

            if(statusCode == HttpStatus.NOT_FOUND.value()) {
                return "errors/error-404";
            }
            else if(statusCode == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                return "errors/error-500";
            }
            else if(statusCode == HttpStatus.FORBIDDEN.value()) {
                return "errors/error-403";
            }
            // Add other status codes as needed
        }
        return "errors/error";
    }

    @GetMapping("/custom-error")
    public String customError(@RequestParam(required = false) String error, Model model) {
        model.addAttribute("error", error);
        return "errors/error-403"; // View name for the error page
    }
}
