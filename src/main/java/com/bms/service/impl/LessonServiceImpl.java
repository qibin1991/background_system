package com.bms.service.impl;

import com.bms.dao.LessonDao;
import com.bms.dto.LessonDto;
import com.bms.dto.PlanDto;
import com.bms.entity.Lesson;
import com.bms.entity.Period;
import com.bms.exception.BaseException;
import com.bms.service.LessonService;
import com.bms.service.PeriodService;
import com.bms.util.FinalName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static com.bms.util.CommonUtils.*;


/**
 * @author 咸鱼
 * @date 2019-07-12 19:11
 */
@Primary
@Service
@Slf4j
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Transactional(rollbackFor = RuntimeException.class)
public class LessonServiceImpl implements LessonService {
    private final LessonDao lessonDao;
    private final PeriodService periodService;

    /**
     * 业务逻辑：
     * 首先，查询当前科目，在当前时间段内，是否安排了其他老师来上
     * 其次，查询当前老师，在当前时间段内，是否安排了其他课程
     */
    @Override
    public boolean addLesson(Lesson lesson) {
        lesson.setWeek(getWeekOfYear(localDateTime2Date(lesson.getStartTime())));
        detectConflicts(lesson);
        return lessonDao.insertLesson(lesson) == 1;
    }

    @Override
    public List<LessonDto> getLessons(Date startTime, Long userId, Long subjectId, Long campusId) {
        Lesson lesson = new Lesson();
        if (startTime != null) {
            lesson.setWeek(getWeekOfYear(startTime));
        }
        if (userId != null) {
            lesson.setUserId(userId);
        }
        if (subjectId != null) {
            lesson.setSubjectId(subjectId);
        }
        if (campusId != null) {
            lesson.setCampusId(campusId);
        }
        List<Lesson> lessons = selectLessons(lesson);
        List<Period> periods = periodService.getAllPeriods();
        Map<String, Map<String, Map<Integer, List<Lesson>>>> map = list2Map(lessons, periods);
        List<LessonDto> lessonDtos = new ArrayList<>();
        map.forEach((week, value) -> {
            LessonDto lessonDto = new LessonDto();
            lessonDtos.add(lessonDto);
            lessonDto.setWeek(week);
            lessonDto.setMonday(getMondayInWeek(week));
            List<PlanDto> plans = new ArrayList<>();
            // 初始化，保证没有数据的行也能显示
            periods.forEach(period -> {
                PlanDto plan = new PlanDto(period.getName(), null, null, null, null, null, null, null);
                plans.add(plan);
            });
            lessonDto.setPlans(plans);
            value.forEach((period, value1) -> plans.forEach(plan -> {
                if (plan.getPeriod().equals(period)) {
                    value1.forEach((index, value2) -> {
                        switch (index) {
                            case 0:
                                plan.setMon(value2);
                                break;
                            case 1:
                                plan.setTue(value2);
                                break;
                            case 2:
                                plan.setWed(value2);
                                break;
                            case 3:
                                plan.setThurs(value2);
                                break;
                            case 4:
                                plan.setFri(value2);
                                break;
                            case 5:
                                plan.setSat(value2);
                                break;
                            case 6:
                                plan.setSun(value2);
                                break;
                            default:
                                break;
                        }
                    });
                }
            }));
        });
        return lessonDtos;
    }

    @Override
    public boolean updateLesson(Lesson lesson) {
        if (lessonDao.selectLessonById(lesson.getId()) == null) {
            throw new BaseException("该课程不存在！");
        }

        // 检测当前课程计划是否和数据库中的已有计划存在冲突
        detectConflicts(lesson);

        lesson.setWeek(getWeekOfYear(localDateTime2Date(lesson.getStartTime())));
        return lessonDao.updateLesson(lesson) == 1;
    }

    @Override
    public boolean removeLesson(String ids) {
        if (StringUtils.isBlank(ids)) {
            throw new BaseException("无待删除课程！");
        }
        String[] idArr = ids.split(",");
        // 当删除失败时，有可能已经删除一部分了，所以需要主动抛出异常，触发事务回滚
        if (lessonDao.deleteLessons(idArr) != idArr.length) {
            throw new BaseException("删除失败！");
        }
        return true;
    }

    private Map<String, Map<String, Map<Integer, List<Lesson>>>> list2Map(List<Lesson> lessons, List<Period> periods) {
        Map<String, Map<String, Map<Integer, List<Lesson>>>> map = new HashMap<>();
        lessons.forEach(lesson -> {
            Map<String, Map<Integer, List<Lesson>>> map1 = map.computeIfAbsent(lesson.getWeek(), k -> new HashMap<>());
            LocalDateTime startLocalDateTime = lesson.getStartTime();
            final int dayOfWeeK = startLocalDateTime.getDayOfWeek().getValue() - 1;
            LocalTime startLocalTime = LocalTime.of(startLocalDateTime.getHour(), startLocalDateTime.getMinute());
            periods.forEach(period -> {
                String[] arr = period.getName().split("-");
                if (arr.length == FinalName.ARR_LENGTH) {
                    LocalTime startRangeLocalTime = parseTime(arr[0]);
                    LocalTime endRangeLocalTime = parseTime(arr[1]);
                    if (!startLocalTime.isBefore(startRangeLocalTime) && !startLocalTime.isAfter(endRangeLocalTime)) {
                        Map<Integer, List<Lesson>> map2 = map1.computeIfAbsent(period.getName(), k -> new HashMap<>());
                        List<Lesson> lessons1 = map2.computeIfAbsent(dayOfWeeK, k -> new ArrayList<>());
                        lessons1.add(lesson);
                    }
                }
            });
        });
        return map;
    }

    /**
     *     检测当前排课计划是否和数据库中已有计划存在冲突（PS：当{@link Lesson}id存在时，说明是更新{@link Lesson}操作，
     * 这时在查询数据库检测冲突时，需要排除当前{@link Lesson}的id，也就是 l.id != #{id}）
     * @param lesson {@link Lesson}
     */
    private void detectConflicts(Lesson lesson) {
        //第一次查询：查询当前科目，在当前时间段内，是否已存在记录（PS：是否安排其他老师来上）
        Lesson tempLesson1 = new Lesson();
        if (lesson.getId() != null) {
            tempLesson1.setId(lesson.getId());
        }
        tempLesson1.setSubjectId(lesson.getSubjectId());
        tempLesson1.setStartTime(lesson.getStartTime());
        tempLesson1.setEndTime(lesson.getEndTime());
        List<Lesson> lessons1 = selectLessons(tempLesson1);
        if (!CollectionUtils.isEmpty(lessons1)) {
            throw new BaseException("科目【" + lessons1.get(0).getSubject().getName() + "】在当前时间段内已安排给【"
                    + lessons1.get(0).getUser().getName() + "】老师！");
        }

        //第二次查询：查询当前老师，在当前时间段内，是否已存在记录（PS：是否安排了其他课程）
        Lesson tempLesson2 = new Lesson();
        if (lesson.getId() != null) {
            tempLesson2.setId(lesson.getId());
        }
        tempLesson2.setUserId(lesson.getUserId());
        tempLesson2.setStartTime(lesson.getStartTime());
        tempLesson2.setEndTime(lesson.getEndTime());
        List<Lesson> lessons2 = selectLessons(tempLesson2);
        if (!CollectionUtils.isEmpty(lessons2)) {
            throw new BaseException("老师【" + lessons2.get(0).getUser().getName() + "】在当前时间段内已给其安排了【"
                    + lessons2.get(0).getSubject().getName() + "】课程！");
        }
    }

    private List<Lesson> selectLessons(Lesson lesson) {
        return lessonDao.selectLessons(lesson);
    }
}
