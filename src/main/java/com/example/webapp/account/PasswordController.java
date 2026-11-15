package com.example.webapp.account;

import com.example.webapp.common.ApiError;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

/** Current-password verification and an atomic hash predicate prevent competing changes. */
@RestController
@RequestMapping("/api/account/password")
public class PasswordController {
    private final MongoTemplate mongo;
    private final PasswordEncoder encoder;
    private final CurrentAccount owner;
    private final SessionRegistry sessions;
    public PasswordController(MongoTemplate mongo, PasswordEncoder encoder, CurrentAccount owner, SessionRegistry sessions) {
        this.mongo=mongo;this.encoder=encoder;this.owner=owner;this.sessions=sessions;
    }
    @PostMapping
    public ResponseEntity<?> change(@Valid @RequestBody PasswordChangeRequest input, HttpServletRequest request, HttpServletResponse response) {
        var account=mongo.findById(owner.id(),Account.class);
        if(account==null || input.currentPassword().getBytes(StandardCharsets.UTF_8).length>72
                || !encoder.matches(input.currentPassword(),account.passwordHash()))
            return invalid("currentPassword","The current password is incorrect.");
        if(!input.newPassword().equals(input.confirmation()))return invalid("confirmation","The passwords do not match.");
        if(input.newPassword().getBytes(StandardCharsets.UTF_8).length>72)return invalid("newPassword","Use at most 72 UTF-8 bytes.");
        if(encoder.matches(input.newPassword(),account.passwordHash()))return invalid("newPassword","Choose a different password from your current one.");
        var result=mongo.updateFirst(Query.query(Criteria.where("_id").is(account.id()).and("passwordHash").is(account.passwordHash())),
                Update.update("passwordHash",encoder.encode(input.newPassword())),Account.class);
        if(result.getModifiedCount()!=1)return ResponseEntity.status(409).body(new ApiError("PASSWORD_CHANGED","Your password changed in another session. Sign in again before retrying."));
        // Expire every registered session for this owner; the filter rejects their next request.
        for(var principal:sessions.getAllPrincipals())if(principal instanceof AccountPrincipal p && p.accountId().equals(account.id()))
            for(var session:sessions.getAllSessions(principal,false))session.expireNow();
        new SecurityContextLogoutHandler().logout(request,response,SecurityContextHolder.getContext().getAuthentication());
        var cookie=new Cookie("JSESSIONID","");cookie.setPath("/");cookie.setMaxAge(0);cookie.setHttpOnly(true);response.addCookie(cookie);
        return ResponseEntity.noContent().build();
    }
    private ResponseEntity<ApiError> invalid(String field,String message) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED","Check the highlighted fields.",Map.of(field,message)));
    }
}
