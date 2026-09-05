package com.leagueakari.team;

import com.leagueakari.config.TeamProperties;
import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import com.leagueakari.dto.riot.RiotAccountDto;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchParticipantMapper;
import com.leagueakari.riot.RiotAccountClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TeamRosterService 单元测试：车队名单（roster）解析契约
 * <p>核心场景——<b>两套 puuid 体系</b>：台服客户端上报的局用腾讯 UUID，
 * MATCH-V5 回填局用 Riot 全局 puuid，成员身份是两者的并集：</p>
 * 覆盖：库内 summoner_name 反查优先、Riot Account-V1 补充、
 * Riot 失败但库内命中时降级不阻塞、两套来源全空才报错、
 * 配置为空的明确报错、空白项跳过、按 riotId 去重、解析结果缓存。
 */
@ExtendWith(MockitoExtension.class)
class TeamRosterServiceTest {

    @Mock
    private RiotAccountClient riotAccountClient;

    @Mock
    private MatchParticipantMapper matchParticipantMapper;

    @InjectMocks
    private TeamRosterService teamRosterService;

    /** 构造指定名单的配置对象 */
    private TeamProperties properties(String... roster) {
        TeamProperties props = new TeamProperties();
        props.setRoster(List.of(roster));
        return props;
    }

    /** 构造 Riot 账号 DTO：puuid 为 Riot 全局标识 */
    private RiotAccountDto account(String puuid) {
        RiotAccountDto dto = new RiotAccountDto();
        dto.setPuuid(puuid);
        dto.setGameName("name-of-" + puuid);
        return dto;
    }

    /** 构造库内参赛者行（模拟按 summoner_name 反查的命中） */
    private MatchParticipant dbRow(String puuid) {
        MatchParticipant row = new MatchParticipant();
        row.setPuuid(puuid);
        return row;
    }

    /** 用例：名单为空时 isConfigured=false，requireMembers 抛业务异常（1101） */
    @Test
    void requireMembers_throwsWhenRosterEmpty() {
        teamRosterService = new TeamRosterService(properties(), riotAccountClient, matchParticipantMapper);

        assertThat(teamRosterService.isConfigured()).isFalse();
        assertThatThrownBy(() -> teamRosterService.requireMembers())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("车队名单未配置");
    }

    /**
     * 用例：库内反查命中腾讯 UUID + Riot API 返回全局 puuid →
     * 成员身份集合是两者的并集（两套来源的局都能归属到人）
     */
    @Test
    void requireMembers_mergesDbIdentityAndRiotIdentity() {
        teamRosterService = new TeamRosterService(
                properties("赌书消得泼茶香#iKun"), riotAccountClient, matchParticipantMapper);
        when(matchParticipantMapper.selectList(any())).thenReturn(List.of(
                dbRow("3e242ccb-b520-5f29-8551-a7ad71b8f629")));
        when(riotAccountClient.searchByRiotId("赌书消得泼茶香#iKun"))
                .thenReturn(account("IZOp3JUS-global-riots-puuid"));

        List<TeamRosterService.RosterMember> members = teamRosterService.requireMembers();

        assertThat(members).hasSize(1);
        TeamRosterService.RosterMember member = members.get(0);
        assertThat(member.getRiotId()).isEqualTo("赌书消得泼茶香#iKun");
        // 两套标识符都在身份集合里，主标识取集合首项（库内命中的腾讯 UUID）
        assertThat(member.getPuuids()).containsExactly(
                "3e242ccb-b520-5f29-8551-a7ad71b8f629", "IZOp3JUS-global-riots-puuid");
        assertThat(member.primaryPuuid()).isEqualTo("3e242ccb-b520-5f29-8551-a7ad71b8f629");
        assertThat(member.owns("IZOp3JUS-global-riots-puuid")).isTrue();
        assertThat(member.owns("陌生-puuid")).isFalse();
    }

    /** 用例：库内未命中（如新成员还没打过同步局）时回退 Riot API 解析 */
    @Test
    void requireMembers_fallsBackToRiotWhenDbMisses() {
        teamRosterService = new TeamRosterService(
                properties("手裂鬼子#tw2"), riotAccountClient, matchParticipantMapper);
        when(matchParticipantMapper.selectList(any())).thenReturn(List.of());
        when(riotAccountClient.searchByRiotId("手裂鬼子#tw2")).thenReturn(account("riot-puuid-b"));

        TeamRosterService.RosterMember member = teamRosterService.requireMembers().get(0);

        assertThat(member.getPuuids()).containsExactly("riot-puuid-b");
        assertThat(member.primaryPuuid()).isEqualTo("riot-puuid-b");
    }

    /** 用例：库内已命中时 Riot 查询失败仅降级（保留库内身份），不阻塞整体解析 */
    @Test
    void requireMembers_degradesWhenRiotFailsButDbHits() {
        teamRosterService = new TeamRosterService(
                properties("手裂鬼子#tw2"), riotAccountClient, matchParticipantMapper);
        when(matchParticipantMapper.selectList(any())).thenReturn(List.of(dbRow("tencent-uuid-b")));
        when(riotAccountClient.searchByRiotId("手裂鬼子#tw2"))
                .thenThrow(new BizException(ErrorCode.RIOT_ACCOUNT_NOT_FOUND, "not found"));

        TeamRosterService.RosterMember member = teamRosterService.requireMembers().get(0);

        assertThat(member.getPuuids()).containsExactly("tencent-uuid-b");
    }

    /** 用例：库内与 Riot 两套来源都查不到 → 整体抛业务异常（1102）并带成员名定位 */
    @Test
    void requireMembers_failsWhenBothSourcesMiss() {
        teamRosterService = new TeamRosterService(properties("消失的人#tw2"), riotAccountClient, matchParticipantMapper);
        when(matchParticipantMapper.selectList(any())).thenReturn(List.of());
        when(riotAccountClient.searchByRiotId("消失的人#tw2"))
                .thenThrow(new BizException(ErrorCode.RIOT_ACCOUNT_NOT_FOUND, "not found"));

        assertThatThrownBy(() -> teamRosterService.requireMembers())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("消失的人#tw2");
    }

    /** 用例：空白项跳过，不触发任何查询 */
    @Test
    void requireMembers_skipsBlankEntries() {
        teamRosterService = new TeamRosterService(
                properties("  ", "手裂鬼子#tw2"), riotAccountClient, matchParticipantMapper);
        when(matchParticipantMapper.selectList(any())).thenReturn(List.of(dbRow("tencent-uuid-b")));

        List<TeamRosterService.RosterMember> members = teamRosterService.requireMembers();

        assertThat(members).hasSize(1);
        verify(riotAccountClient, times(1)).searchByRiotId(anyString());
    }

    /** 用例：同一成员出现两次（配置重复）按 riotId 去重 */
    @Test
    void requireMembers_deduplicatesSameRiotId() {
        teamRosterService = new TeamRosterService(
                properties("旧名#tw2", "旧名#tw2"), riotAccountClient, matchParticipantMapper);
        when(matchParticipantMapper.selectList(any())).thenReturn(List.of());
        when(riotAccountClient.searchByRiotId(anyString())).thenReturn(account("riot-puuid-x"));

        assertThat(teamRosterService.requireMembers()).hasSize(1);
    }

    /** 用例：解析结果缓存——第二次调用不再触发任何查询 */
    @Test
    void requireMembers_cachesResolution() {
        teamRosterService = new TeamRosterService(properties("手裂鬼子#tw2"), riotAccountClient, matchParticipantMapper);
        when(matchParticipantMapper.selectList(any())).thenReturn(List.of(dbRow("tencent-uuid-b")));

        teamRosterService.requireMembers();
        teamRosterService.requireMembers();

        verify(matchParticipantMapper, times(1)).selectList(any());
        verify(riotAccountClient, times(1)).searchByRiotId(anyString());
    }
}
