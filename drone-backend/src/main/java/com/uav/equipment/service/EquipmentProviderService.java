package com.uav.equipment.service;

import com.uav.equipment.mapper.EquipmentProviderRepository;
import com.uav.equipment.pojo.dto.ProviderLoginDTO;
import com.uav.equipment.pojo.entity.EquipmentProvider;
import com.uav.equipment.pojo.vo.*;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.PasswordUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;

/** REQ-PROV-ACCT-001: separate provider identity namespace; no public registration or seeded accounts. */
@Service
public class EquipmentProviderService {
    public static final int PROVIDER_ROLE = 3;
    private final EquipmentProviderRepository providers;
    private final JwtUtil jwt;
    public EquipmentProviderService(EquipmentProviderRepository providers, JwtUtil jwt) { this.providers = providers; this.jwt = jwt; }
    @Transactional(readOnly = true)
    public ProviderLoginVO login(ProviderLoginDTO input) {
        var provider = providers.findByUsername(normalize(input.username())).filter(EquipmentProvider::isEnabled).orElseThrow(this::unauthorized);
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72 || !PasswordUtil.matches(input.password(), provider.getPasswordHash())) throw unauthorized();
        return new ProviderLoginVO(jwt.generateToken(provider.getId(), provider.getUsername(), PROVIDER_ROLE), view(provider));
    }
    @Transactional(readOnly = true)
    public ProviderAccountVO me(Long caller) { return view(requireEnabled(caller)); }
    @Transactional(readOnly = true)
    public ProviderAccountPageVO list(int page) {
        var rows = providers.findAll(EquipmentAccess.page(page, "createdAt"));
        return new ProviderAccountPageVO(rows.stream().map(EquipmentProviderService::view).toList(), page, rows.hasNext());
    }
    @Transactional
    public ProviderAccountVO enabled(Long id, boolean enabled) {
        var p = providers.lockProvider(id).orElseThrow(EquipmentAccess::missing);
        p.setEnabled(enabled); return view(providers.saveAndFlush(p));
    }
    EquipmentProvider requireEnabled(Long id) {
        return providers.findById(id).filter(EquipmentProvider::isEnabled).orElseThrow(EquipmentAccess::missing);
    }
    EquipmentProvider lockEnabled(Long id) {
        return providers.lockProvider(id).filter(EquipmentProvider::isEnabled).orElseThrow(EquipmentAccess::missing);
    }
    // Internal provisioning primitive. Account-opening HTTP policy is kept separate from borrowing.
    @Transactional
    public ProviderAccountVO provision(String username, String password, String displayName) {
        String name = normalize(username);
        if (name.isBlank() || name.length() > 64 || password == null || password.isBlank()
                || password.getBytes(StandardCharsets.UTF_8).length > 72 || displayName == null || displayName.isBlank() || displayName.trim().length() > 100) {
            throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        }
        if (providers.findByUsername(name).isPresent()) throw EquipmentAccess.conflict("服务商用户名已存在");
        var p = new EquipmentProvider(); p.setUsername(name); p.setPasswordHash(PasswordUtil.hash(password));
        p.setDisplayName(displayName.trim()); p.setCreatedAt(LocalDateTime.now());
        try { return view(providers.saveAndFlush(p)); }
        catch (org.springframework.dao.DataIntegrityViolationException e) { throw EquipmentAccess.conflict("服务商用户名已存在"); }
    }
    static ProviderAccountVO view(EquipmentProvider p) { return new ProviderAccountVO(p.getId(), p.getUsername(), p.getDisplayName(), p.isEnabled()); }
    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private BusinessException unauthorized() { return new BusinessException(HttpStatus.UNAUTHORIZED, ApiErrorCode.NO_PERMISSION, "用户名或密码错误，或账号不可用"); }
}
