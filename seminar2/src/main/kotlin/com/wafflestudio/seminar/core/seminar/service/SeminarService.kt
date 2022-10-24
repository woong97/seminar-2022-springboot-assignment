package com.wafflestudio.seminar.core.seminar.service

import com.wafflestudio.seminar.common.Seminar400
import com.wafflestudio.seminar.common.Seminar403
import com.wafflestudio.seminar.common.Seminar404
import com.wafflestudio.seminar.common.Seminar409
import com.wafflestudio.seminar.core.profile.database.ParticipantProfileRepository
import com.wafflestudio.seminar.core.seminar.api.request.ParticipateSeminarRequest
import com.wafflestudio.seminar.core.seminar.api.request.SeminarRequest
import com.wafflestudio.seminar.core.seminar.api.response.InstructorInfo
import com.wafflestudio.seminar.core.seminar.api.response.SeminarInfo
import com.wafflestudio.seminar.core.seminar.api.response.SeminarResponse
import com.wafflestudio.seminar.core.seminar.api.response.SeminarsQueryResponse
import com.wafflestudio.seminar.core.seminar.database.SeminarEntity
import com.wafflestudio.seminar.core.seminar.database.SeminarRepository
import com.wafflestudio.seminar.core.seminar.database.UserSeminarEntity
import com.wafflestudio.seminar.core.seminar.database.UserSeminarRepository
import com.wafflestudio.seminar.core.user.database.UserEntity
import com.wafflestudio.seminar.core.user.type.UserRole
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.*
import javax.transaction.Transactional

@Service
class SeminarService(
    private val seminarRepository: SeminarRepository,
    private val userSeminarRepository: UserSeminarRepository,
    private val participateProfileRepository: ParticipantProfileRepository
){
    @Transactional
    fun createSeminar(user: UserEntity, seminarRequest: SeminarRequest) : SeminarInfo {
        validateCreateSeminar(user, seminarRequest)
        val now = LocalDateTime.now()
        val seminarEntity = seminarRepository.save(
            SeminarEntity(
                createdAt = now,
                name = seminarRequest.name,
                capacity = seminarRequest.capacity,
                count = seminarRequest.count,
                time = seminarRequest.time,
                online = seminarRequest.online
            )
        )
        userSeminarRepository.save(
            UserSeminarEntity(
                createdAt = now, 
                user=user, 
                seminar = seminarEntity,
                isActive = true,
                isParticipant = false
            )
        )
        return SeminarInfo.from(seminarEntity, InstructorInfo(
            id = user.id, username = user.username, email = user.email, joinedAt=now
        ))
    }
    
    @Transactional
    fun participateSeminar(seminarId: Long, user: UserEntity, request: ParticipateSeminarRequest) : SeminarResponse {
        if (!UserRole.values().any { it.name == request.role.toString() }) {
            throw Seminar400("잘못된 role이 입력됐습니다")
        }
        val seminar = seminarRepository.findByIdOrNull(seminarId) ?: throw Seminar404("해당 seminar id가 존재하지 않습니다")
        if (!user.isRegistered) throw Seminar403("활성회원이 아닙니다")
        
        if (user.role == UserRole.PARTICIPANT && request.role == UserRole.INSTRUCTOR) {
            throw Seminar400("해당 유저는 Instructor 권한이 없습니다")
        }
        
        if (request.role == UserRole.PARTICIPANT) {
            participateProfileRepository.findByUserId(user.id) ?: throw Seminar403("ParticipateProfile이 없는 유저입니다")
            val participatedUserSeminars = userSeminarRepository.findUserSeminarsBySeminarId(seminarId)

            val nParticipant = participatedUserSeminars.filter { it.isParticipant }.count()
            if (nParticipant >= seminar.capacity) {
                throw Seminar400("정원이 다 찼습니다")
            }
        }
        val seminarsConnectedByUser = userSeminarRepository.findUserSeminarsByUserId(user.id)
        
        if (request.role == UserRole.PARTICIPANT) {
            seminarsConnectedByUser.forEach {
                if (it.isActive && it.seminar!!.id == seminarId) throw Seminar400("이미 이 세미나에 참여중입니다")
                if (!it.isActive && it.seminar!!.id == seminarId) throw Seminar400("중도 포기한 세미나에 다시 참여할 수 없습니다")
            }
            userSeminarRepository.save(
                UserSeminarEntity(
                    createdAt = LocalDateTime.now(),
                    user=user,
                    seminar = seminar,
                    isActive = true,
                    isParticipant = true
                )
            )
        } else {
            seminarsConnectedByUser.forEach {
                if (it.isActive && it.seminar!!.id == seminarId) throw Seminar400("이미 이 세미나에 참여중입니다")
                if (it.isActive && !it.isParticipant) throw Seminar400("이미 Instructor로 담당하고 있는 세미나가 존재합니다")
            }
            userSeminarRepository.save(
                UserSeminarEntity(
                    createdAt = LocalDateTime.now(),
                    user=user,
                    seminar = seminar,
                    isActive = true,
                    isParticipant = false
                )
            )
        }
        return SeminarResponse.from(seminar)
    }
    
    @Transactional
    fun dropSeminar(user: UserEntity, seminarId: Long) : SeminarResponse { 
        val seminar = seminarRepository.findByIdOrNull(seminarId) ?: throw Seminar404("해당 seminar id가 존재하지 않습니다")
        val userSeminarEntity = userSeminarRepository.findUserSeminarByUserIdAndSeminarId(user.id, seminarId)
            ?: return SeminarResponse.from(seminar)
        if (!userSeminarEntity.isParticipant) throw Seminar403("세미나 진행자는 세미나를 드랍할 수 없습니다")
        val now = LocalDateTime.now()
        userSeminarEntity.isActive = false
        userSeminarEntity.modifiedAt = now
        userSeminarEntity.droppedAt = now
        return SeminarResponse.from(seminar)
    }
    
    @Transactional
    fun deleteSeminar(user: UserEntity, seminarId: Long) {
        val seminar = seminarRepository.findByIdOrNull(seminarId) ?: throw Seminar404("해당 seminar id가 존재하지 않습니다")
        val userSeminarEntity = userSeminarRepository.findUserSeminarByUserIdAndSeminarId(user.id, seminarId)
            ?: throw Seminar403("세미나를 없앨 권한이 없습니다")
        if (!userSeminarEntity.isActive) throw Seminar403("비활성화된 사용자는 세미나를 없앨 수 없습니다")
        if (userSeminarEntity.isParticipant) throw Seminar403("참여자는 세미나를 없앨 권한이 없습니다")
        seminarRepository.delete(seminar)
    }
    
    fun getSeminar(seminarId: Long) : SeminarResponse {
        val seminar = seminarRepository.findByIdOrNull(seminarId) ?: throw Seminar404("해당 seminar id가 존재하지 않습니다")
        return SeminarResponse.from(seminar)
    }
    
    fun getSeminarsByQueryParam(name: String?, order: String?) : MutableList<SeminarsQueryResponse> {
        val orderBool: Boolean = if (order == null) {
            false
        } else {
            order == "earliest"
        }
        return seminarRepository.findSeminarsByNameAndOrder(name, orderBool)
    }
    
    fun validateCreateSeminar(user: UserEntity, seminarRequest: SeminarRequest) {
        if (user.role == UserRole.PARTICIPANT) {
            throw Seminar403("참가자는 세미나를 생성할 수 없습니다")
        }
        val seminarEntityByName : Optional<SeminarEntity> = seminarRepository.findByName(seminarRequest.name)
        if (seminarEntityByName.isPresent) {
            throw Seminar409("중복된 세미나 이름입니다")
        }
    }
}