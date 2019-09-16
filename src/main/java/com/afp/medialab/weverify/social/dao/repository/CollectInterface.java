package com.afp.medialab.weverify.social.dao.repository;

import com.afp.medialab.weverify.social.dao.entity.CollectHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.transaction.Transactional;
import java.util.Date;
import java.util.List;

public interface CollectInterface extends JpaRepository<CollectHistory, Integer> {

    CollectHistory findCollectHistoryBySession(String session);

    CollectHistory findCollectHistoryByQuery(String query);

    List<CollectHistory> findAll();

    @Modifying
    @Transactional
    @Query("update CollectHistory collect set collect.status = :status where collect.session = :session")
    void updateCollectStatus(@Param("session") String session, @Param("status") String status);

    @Modifying
    @Transactional
    @Query("update CollectHistory collect set collect.processStart = :processStart where collect.session = :session")
    void updateCollectProcessStart(@Param("session") String session, @Param("processStart") Date processStart);

    @Modifying
    @Transactional
    @Query("update CollectHistory collect set collect.processEnd = :processEnd where collect.session = :session")
    void updateCollectProcessEnd(@Param("session") String session, @Param("processEnd") Date processEnd);
}
