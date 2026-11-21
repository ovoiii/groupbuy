package org.jeecg.modules.groupbuy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.groupbuy.entity.GbMember;
import org.jeecg.modules.groupbuy.entity.GbOrder;
import org.jeecg.modules.groupbuy.entity.GbPoint;
import org.jeecg.modules.groupbuy.mapper.GbMemberMapper;
import org.jeecg.modules.groupbuy.mapper.GbOrderMapper;
import org.jeecg.modules.groupbuy.mapper.GbPointMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static org.jeecg.modules.groupbuy.service.GbCoreService.fail;

/**
 * 小程序登录：微信登录必须带手机号（提货核对、团长联系、团长身份都靠手机号）。
 * <ol>
 *   <li>wx.login → /login：这个微信已经绑过手机号就直接登录；否则返回一张 10 分钟有效的登录票据，不建账号。</li>
 *   <li>用户点「手机号快捷登录」→ /login/phone（票据 + 手机号 code）：按手机号找账号，找到就把微信绑上去，找不到就新建。</li>
 * </ol>
 * 手机号只来自微信验证（或开发登录），用户不能自己改——团长是按手机号认的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbLoginService {
    private final GbMemberMapper memberMapper;
    private final GbOrderMapper orderMapper;
    private final GbPointMapper pointMapper;

    /** 这个微信绑过手机号的账号，没有返回 null */
    public GbMember byOpenid(String openid) {
        GbMember m = memberMapper.selectOne(new LambdaQueryWrapper<GbMember>().eq(GbMember::getOpenid, openid));
        return m != null && m.getPhone() != null ? m : null;
    }

    /** 微信 + 验证过的手机号 → 账号 */
    @Transactional(rollbackFor = Exception.class)
    public GbMember bind(String openid, String phone, String sourcePointId) {
        GbMember byPhone = memberMapper.selectOne(new LambdaQueryWrapper<GbMember>().eq(GbMember::getPhone, phone));
        GbMember byOpenid = memberMapper.selectOne(new LambdaQueryWrapper<GbMember>().eq(GbMember::getOpenid, openid));
        if (byPhone != null) {
            if (byOpenid != null && !byOpenid.getId().equals(byPhone.getId())) {
                // 这个微信以前建过一个没手机号的账号（老版本登录留下的）：没下过单就删掉，下过单的不自动合并
                if (byOpenid.getPhone() != null || orderMapper.selectCount(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getMemberId, byOpenid.getId())) > 0)
                    throw fail("这个微信已经绑定了别的账号，请联系客服处理");
                memberMapper.deleteById(byOpenid.getId());
            }
            if (!openid.equals(byPhone.getOpenid())) {
                // 手机号是微信验证过的，说明是本人：换了微信也允许，旧微信从此登录不了这个账号
                if (byPhone.getOpenid() != null) log.info("[团购] 账号 {} 换绑微信", byPhone.getId());
                memberMapper.update(null, new LambdaUpdateWrapper<GbMember>().eq(GbMember::getId, byPhone.getId()).set(GbMember::getOpenid, openid));
                byPhone.setOpenid(openid);
            }
            return byPhone;
        }
        if (byOpenid != null) {
            memberMapper.update(null, new LambdaUpdateWrapper<GbMember>().eq(GbMember::getId, byOpenid.getId()).set(GbMember::getPhone, phone));
            byOpenid.setPhone(phone);
            return byOpenid;
        }
        return create(phone, openid, null, sourcePointId);
    }

    /** 短信验证过的手机号（没有微信时）：有账号就登录，没有就新建 */
    public GbMember byPhone(String phone, String sourcePointId) {
        GbMember m = memberMapper.selectOne(new LambdaQueryWrapper<GbMember>().eq(GbMember::getPhone, phone));
        if (m != null) return m;
        try {
            return create(phone, null, null, sourcePointId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 同一个号同时登录两次
            return memberMapper.selectOne(new LambdaQueryWrapper<GbMember>().eq(GbMember::getPhone, phone));
        }
    }

    /** 新建账号；从某个提货点的分享进来的，记下来源并默认选这个提货点 */
    public GbMember create(String phone, String openid, String nickname, String sourcePointId) {
        GbMember m = new GbMember();
        m.setPhone(phone);
        m.setOpenid(openid);
        m.setNickname(nickname == null || nickname.isBlank() ? "用户" + phone.substring(7) : nickname.trim());
        m.setFirstOrderDone(0);
        if (sourcePointId != null && !sourcePointId.isBlank()) {
            GbPoint p = pointMapper.selectById(sourcePointId);
            if (p != null && "ok".equals(p.getStatus())) {
                m.setSourcePointId(p.getId());
                m.setDefaultPointId(p.getId());
            }
        }
        memberMapper.insert(m);
        return m;
    }
}
