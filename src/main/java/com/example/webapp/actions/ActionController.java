package com.example.webapp.actions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** All routes use server-owned identities; POST/PATCH are protected by the existing CSRF filter. */
@RestController
@RequestMapping("/api")
public class ActionController {
    private final ActionService service;
    public ActionController(ActionService service){this.service=service;}
    @GetMapping("/applications/{id}/actions")
    public ActionService.Page forApplication(@PathVariable String id,@RequestParam(required=false)JobAction.Status status,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.list(id,status,page,size);}
    @GetMapping("/actions")
    public ActionService.Page all(@RequestParam(required=false)JobAction.Status status,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.list(null,status,page,size);}
    // Separate route keeps the existing per-action and list contracts unchanged.
    @GetMapping("/dashboard/actions")
    public ActionService.AgendaPage agenda(@RequestParam ActionService.AgendaSection section,
            @RequestParam(defaultValue="0") int page) { return service.agenda(section, page); }
    @GetMapping("/actions/{id}")public ActionResponse get(@PathVariable String id){return service.get(id);}
    @PostMapping("/applications/{id}/actions")@ResponseStatus(HttpStatus.CREATED)
    public ActionResponse create(@PathVariable String id,@Valid @RequestBody ActionRequest request){return service.create(id,request);}
    @PatchMapping("/actions/{id}")
    public ActionResponse update(@PathVariable String id,@Valid @RequestBody ActionRequest request){return service.update(id,request);}
    @PostMapping("/actions/{id}/complete")
    public ActionResponse complete(@PathVariable String id,@Valid @RequestBody VersionRequest request){return service.finish(id,request.version(),false);}
    @PostMapping("/actions/{id}/cancel")
    public ActionResponse cancel(@PathVariable String id,@Valid @RequestBody VersionRequest request){return service.finish(id,request.version(),true);}
    public record VersionRequest(@NotNull @PositiveOrZero Long version){}
}
