package com.uav.flight.service;

import com.tencentyun.TLSSigAPIv2;
import com.uav.flight.pojo.entity.DispatchLiveSession;
import com.uav.flight.pojo.vo.DispatchLiveCredentialsVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** REQ-SHIP-EXEC-004: room-bound receive-only tickets; secrets never leave the backend. */
@Service
public class DispatchLiveSigner {
    private final long appId;
    private final String secret;
    private final boolean permissions;
    public DispatchLiveSigner(@Value("${trtc.sdk-app-id:0}") long appId,
            @Value("${trtc.secret-key:}") String secret,
            @Value("${dispatch.live.room-permissions-enabled:false}") boolean permissions) {
        this.appId = appId; this.secret = secret; this.permissions = permissions;
    }
    public boolean available() {
        // Reuse the existing TRTC application; room permissions still need explicit cloud confirmation.
        return permissions && appId > 0 && appId <= Integer.MAX_VALUE && !secret.isBlank();
    }
    public DispatchLiveCredentialsVO issue(DispatchLiveSession s, boolean publisher) {
        if (!available()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.TRTC_CREDENTIAL_FAILED);
        String room = "dl_" + s.getId().replace("-", "");
        String anchor = "p_" + s.getId().replace("-", "").substring(0, 28);
        String user = publisher ? anchor : "v_" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        var signer = new TLSSigAPIv2(appId, secret);
        // Tencent room privileges: anchor=create+enter+send video; viewer=enter+receive video. No audio.
        return new DispatchLiveCredentialsVO(s.getId(), s.getConnectionId(), appId, room, user, anchor,
                signer.genUserSig(user, 120), signer.genPrivateMapKeyWithStringRoomID(user, 120, room, publisher ? 19 : 34),
                System.currentTimeMillis() + 120_000, s.getLeaseExpiresAt());
    }
}
