package com.uav.dispatch.controller;

import com.uav.dispatch.pojo.dto.DispatchAssignmentDTO;
import com.uav.dispatch.pojo.dto.DispatchClaimDTO;
import com.uav.dispatch.pojo.vo.DispatchHallVO;
import com.uav.dispatch.pojo.vo.DispatchOrderVO;
import com.uav.dispatch.service.DispatchMatchingService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/dispatch/pilot")
@RequireRole(1)
@Tag(name = "Dispatch Pilot API", description = "真实先接者得和准备/到场状态，不要求绑定设备；付款与环境评估仍沿用当前标注的模拟通道")
public class DispatchPilotController {
    private final DispatchMatchingService matching;
    public DispatchPilotController(DispatchMatchingService matching) { this.matching = matching; }
    @GetMapping("/hall")
    @Operation(summary = "派单大厅", description = "分页公开已付定金且等待接单的订单，不返回联系人、电话、备注、资金流水；不表示附近推送或真实收入测算")
    public Result<DispatchHallVO> hall(@RequestParam(defaultValue = "0") @Min(0) int page) {
        return Result.success(matching.hall(page));
    }
    @GetMapping("/orders")
    @Operation(summary = "我接到的派单", description = "本人当前指派及需求方取消的最近50笔；飞手主动退出后不再具有完整运单读取权限")
    public Result<List<DispatchOrderVO>> mine() { return Result.success(matching.mine(UserContext.getUserId())); }
    @GetMapping("/orders/{orderId}")
    @Operation(summary = "读取本人派单", description = "仅当前指派飞手读取完整运单，含双方联系人和场地；非接单者404")
    public Result<DispatchOrderVO> get(@PathVariable String orderId) { return Result.success(matching.get(UserContext.getUserId(), orderId)); }
    @PostMapping("/orders/{orderId}/claim")
    @Operation(summary = "立即接单", description = "传大厅offerRevision；服务端锁定，只有一人成功；其他人409。相同飞手相同卡片的成功重试幂等，旧卡片不能在重新派单后再次抢走订单")
    public Result<DispatchOrderVO> claim(@PathVariable String orderId, @Valid @RequestBody DispatchClaimDTO input) {
        return Result.success(matching.claim(UserContext.getUserId(), orderId, input.offerRevision()));
    }
    @PostMapping("/orders/{orderId}/arrive")
    @Operation(summary = "标记已到场", description = "仅当前接单飞手及当前assignmentVersion，更新真实数据库状态；不代表系统验证了GPS到场或货物交接")
    public Result<DispatchOrderVO> arrive(@PathVariable String orderId, @Valid @RequestBody DispatchAssignmentDTO input) {
        return Result.success(matching.arrive(UserContext.getUserId(), orderId, input.assignmentVersion()));
    }
    @PostMapping("/orders/{orderId}/release")
    @Operation(summary = "取消接单并重新派单", description = "准备或到场阶段，传本人assignmentVersion；回到大厅且保留已付定金，不收取消费、不限制次数；历史重试不影响后续接单者；成功data=null")
    public Result<Void> release(@PathVariable String orderId, @Valid @RequestBody DispatchAssignmentDTO input) {
        matching.release(UserContext.getUserId(), orderId, input.assignmentVersion());
        return Result.success();
    }
}
