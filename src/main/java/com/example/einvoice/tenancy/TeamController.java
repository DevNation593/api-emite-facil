package com.example.einvoice.tenancy;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.tenancy.TeamService.Member;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/members")
public class TeamController {

	private final TeamService service;

	public TeamController(TeamService service) {
		this.service = service;
	}

	record ChangeRoleRequest(@NotNull Role role) {
	}

	@GetMapping
	List<Member> list(TenantAccess access) {
		return service.list(access);
	}

	@PutMapping("/{userId}/role")
	Member changeRole(TenantAccess access, @PathVariable UUID userId, @Valid @RequestBody ChangeRoleRequest request) {
		return service.changeRole(access, userId, request.role());
	}

	@DeleteMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void remove(TenantAccess access, @PathVariable UUID userId) {
		service.remove(access, userId);
	}

}
