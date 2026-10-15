package io.github.kwebmn.rozklad.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ScheduleDao {
    @Query("SELECT * FROM subscriptions ORDER BY id")
    abstract fun observeSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY id")
    abstract suspend fun subscriptions(): List<SubscriptionEntity>

    @Insert
    abstract suspend fun insertSubscription(entity: SubscriptionEntity): Long

    @Update
    abstract suspend fun updateSubscription(entity: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    protected abstract suspend fun deleteSubscriptionRow(id: Long)

    @Query("DELETE FROM lessons WHERE subscriptionId = :subscriptionId")
    protected abstract suspend fun deleteAllLessons(subscriptionId: Long)

    @Transaction
    open suspend fun deleteSubscription(id: Long) {
        deleteAllLessons(id)
        deleteSubscriptionRow(id)
    }

    @Query(
        "SELECT * FROM lessons WHERE subscriptionId = :subscriptionId AND date BETWEEN :from AND :to " +
            "ORDER BY date, pairNo, slot",
    )
    abstract fun observeLessons(subscriptionId: Long, from: Long, to: Long): Flow<List<LessonEntity>>

    @Query(
        "SELECT * FROM lessons WHERE subscriptionId = :subscriptionId AND date BETWEEN :from AND :to " +
            "ORDER BY date, pairNo, slot",
    )
    abstract suspend fun lessons(subscriptionId: Long, from: Long, to: Long): List<LessonEntity>

    @Query("DELETE FROM lessons WHERE subscriptionId = :subscriptionId AND date BETWEEN :from AND :to")
    protected abstract suspend fun deleteLessons(subscriptionId: Long, from: Long, to: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertLessons(lessons: List<LessonEntity>)

    @Transaction
    open suspend fun replaceLessons(subscriptionId: Long, from: Long, to: Long, lessons: List<LessonEntity>) {
        deleteLessons(subscriptionId, from, to)
        insertLessons(lessons)
    }
}
