package com.fun.ai.claw.plane.repository;

import com.fun.ai.claw.plane.model.ManagedSkillAssetRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class ManagedSkillAssetRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<ManagedSkillAssetRecord> rowMapper = (rs, rowNum) -> new ManagedSkillAssetRecord(
            rs.getString("skill_key"),
            rs.getString("skill_md")
    );

    public ManagedSkillAssetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ManagedSkillAssetRecord> findEnabledByInstanceId(UUID instanceId) {
        return jdbcTemplate.query("""
                        select sb.skill_key,
                               sb.skill_md
                        from instance_skill_binding isb
                        join skill_baseline sb on sb.skill_key = isb.skill_key
                        where isb.instance_id = ?
                          and sb.enabled = true
                        order by sb.skill_key asc
                        """,
                rowMapper,
                instanceId
        );
    }
}
