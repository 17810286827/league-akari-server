package com.leagueakari.service;

import com.leagueakari.config.TeamProperties;
import com.leagueakari.dto.RiotAccountDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TeamRosterService 单元测试：车队名单（roster）解析契约
 * <p>覆盖：配置为空的明确报错、按 "昵称#tag" 逐个解析为 puuid、
 * 空白项跳过、重复 puuid 去重、解析结果缓存、单个成员解析失败的异常透出。</p>
 */
@ExtendWith(MockitoExtension.class)
class TeamRosterServiceTest {

    @Mock
    private RiotAccountClient riotAccountClient;

    @InjectMocks
    private TeamRosterService teamRosterService;

    /** 构造指定名单的配置对象 */
    private TeamProperties properties(String... roster) {
        TeamProperties props = new TeamProperties();
        props.setRoster(List.of(roster));
        return props;
    }

    /** 构造 Riot 账号 DTO：puuid 为身份键 */
    private RiotAccountDto account(String puuid) {
        RiotAccountDto dto = new RiotAccountDto();
        dto.setPuuid(puuid);
        dto.setGameName("name-of-" + puuid);
        return dto;
    }

    /** 用例：名单为空时 isConfigured=false，requireMembers 抛出带提示的参数异常 */
    @Test
    void requireMembers_throwsWhenRosterEmpty() {
        teamRosterService = new TeamRosterService(properties(), riotAccountClient);

        assertThat(teamRosterService.isConfigured()).isFalse();
        assertThatThrownBy(() -> teamRosterService.requireMembers())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("车队名单未配置");
    }

    /** 用例：名单逐项解析为（puuid, riotId）成员，顺序保持配置顺序 */
    @Test
    void requireMembers_resolvesEachRiotIdToPuuid() {
        teamRosterService = new TeamRosterService(
                properties("赌书消得泼茶香#iKun", "手裂鬼子#tw2"), riotAccountClient);
        when(riotAccountClient.searchByRiotId("赌书消得泼茶香#iKun")).thenReturn(account("puuid-a"));
        when(riotAccountClient.searchByRiotId("手裂鬼子#tw2")).thenReturn(account("puuid-b"));

        List<TeamRosterService.RosterMember> members = teamRosterService.requireMembers();

        assertThat(members).hasSize(2);
        assertThat(members.get(0).puuid()).isEqualTo("puuid-a");
        assertThat(members.get(0).riotId()).isEqualTo("赌书消得泼茶香#iKun");
        assertThat(members.get(1).puuid()).isEqualTo("puuid-b");
    }

    /** 用例：空白项跳过，不触发 Riot 查询 */
    @Test
    void requireMembers_skipsBlankEntries() {
        teamRosterService = new TeamRosterService(
                properties("  ", "手裂鬼子#tw2"), riotAccountClient);
        when(riotAccountClient.searchByRiotId("手裂鬼子#tw2")).thenReturn(account("puuid-b"));

        List<TeamRosterService.RosterMember> members = teamRosterService.requireMembers();

        assertThat(members).hasSize(1);
        verify(riotAccountClient, times(1)).searchByRiotId(anyString());
    }

    /** 用例：两个名字解析到同一 puuid（改名/重名场景）时按 puuid 去重 */
    @Test
    void requireMembers_deduplicatesSamePuuid() {
        teamRosterService = new TeamRosterService(
                properties("旧名#tw2", "新名#tw2"), riotAccountClient);
        when(riotAccountClient.searchByRiotId("旧名#tw2")).thenReturn(account("puuid-same"));
        when(riotAccountClient.searchByRiotId("新名#tw2")).thenReturn(account("puuid-same"));

        assertThat(teamRosterService.requireMembers()).hasSize(1);
    }

    /** 用例：解析结果缓存——第二次调用不再触发 Riot 客户端 */
    @Test
    void requireMembers_cachesResolution() {
        teamRosterService = new TeamRosterService(properties("手裂鬼子#tw2"), riotAccountClient);
        when(riotAccountClient.searchByRiotId("手裂鬼子#tw2")).thenReturn(account("puuid-b"));

        teamRosterService.requireMembers();
        teamRosterService.requireMembers();

        verify(riotAccountClient, times(1)).searchByRiotId(anyString());
    }

    /** 用例：单个成员解析失败（改名被回收等）按规格转参数异常（400 语义）并带成员名定位 */
    @Test
    void requireMembers_wrapsResolutionFailure() {
        teamRosterService = new TeamRosterService(properties("消失的人#tw2"), riotAccountClient);
        when(riotAccountClient.searchByRiotId("消失的人#tw2"))
                .thenThrow(new RiotAccountNotFoundException("not found"));

        assertThatThrownBy(() -> teamRosterService.requireMembers())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("消失的人#tw2");
    }
}
