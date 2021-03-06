package co.touchlab.sessionize.db

import co.touchlab.droidcon.db.Database
import co.touchlab.droidcon.db.Session
import co.touchlab.droidcon.db.SessionWithRoom
import co.touchlab.sessionize.api.parseSessionsFromDays
import co.touchlab.sessionize.jsondata.Speaker
import co.touchlab.sessionize.platform.logException
import co.touchlab.stately.concurrency.AtomicReference
import co.touchlab.stately.concurrency.value
import co.touchlab.stately.freeze
import com.squareup.sqldelight.Query
import com.squareup.sqldelight.db.SqlDriver
import kotlinx.serialization.json.JSON
import kotlinx.serialization.list

class SessionizeDbHelper {

    private val driverRef = AtomicReference<SqlDriver?>(null)
    private val dbRef = AtomicReference<Database?>(null)

    fun initDatabase(sqlDriver: SqlDriver) {
        driverRef.value = sqlDriver.freeze()
        dbRef.value = Database(sqlDriver, Session.Adapter(
                startsAtAdapter = DateAdapter(), endsAtAdapter = DateAdapter()
        )).freeze()
    }

    internal fun dbClear() {
        dbRef.value = null
        driverRef.value?.close()
        driverRef.value = null
    }

    internal val instance: Database
        get() = dbRef.value!!

    fun getSessionsQuery(): Query<SessionWithRoom> = instance.sessionQueries.sessionWithRoom()

    fun primeAll(speakerJson: String, scheduleJson: String) {
        instance.sessionQueries.transaction {
            try {
                primeSpeakers(speakerJson)
                primeSessions(scheduleJson)
            } catch (e: Exception) {
                logException(e)
                throw e
            }
        }
    }

    private fun primeSpeakers(speakerJson: String) {
        val speakers = JSON.nonstrict.parse(Speaker.serializer().list, speakerJson)//DefaultData.parseSpeakers(speakerJson)

        for (speaker in speakers) {
            var twitter: String? = null
            var linkedIn: String? = null
            var blog: String? = null
            var other: String? = null
            var companyWebsite: String? = null


            for (link in speaker.links) {

                if (link.linkType == "Twitter") {
                    twitter = link.url
                } else if (link.linkType == "LinkedIn") {
                    linkedIn = link.url
                } else if (link.linkType == "Blog") {
                    blog = link.url
                } else if (link.linkType == "Other") {
                    other = link.url
                } else if (link.linkType == "Company_Website") {
                    companyWebsite = link.url
                }

            }

            instance.userAccountQueries.insertUserAccount(
                    speaker.id,
                    speaker.fullName,
                    speaker.bio,
                    speaker.tagLine,
                    speaker.profilePicture,
                    twitter,
                    linkedIn,
                    if (!companyWebsite.isNullOrEmpty()) {
                        companyWebsite
                    } else if (!blog.isNullOrEmpty()) {
                        blog
                    } else {
                        other
                    }
            )
        }
    }

    private fun primeSessions(scheduleJson: String) {
        val sessions = parseSessionsFromDays(scheduleJson)

        instance.sessionSpeakerQueries.deleteAll()
        val allSessions = instance.sessionQueries.allSessions().executeAsList()

        val newIdSet = HashSet<String>()

        for (session in sessions) {
            instance.roomQueries.insertRoot(session.roomId!!.toLong(), session.room)

            newIdSet.add(session.id)

            val dbSession = instance.sessionQueries.sessionById(session.id).executeAsOneOrNull()

            if (dbSession == null) {
                instance.sessionQueries.insert(
                        session.id,
                        session.title,
                        session.descriptionText ?: "",
                        instance.sessionAdapter.startsAtAdapter.decode(session.startsAt!!),
                        instance.sessionAdapter.endsAtAdapter.decode(session.endsAt!!),
                        if (session.isServiceSession) {
                            1
                        } else {
                            0
                        }, session.roomId!!.toLong()
                )
            } else {
                instance.sessionQueries.update(
                        title = session.title,
                        description = session.descriptionText ?: "",
                        startsAt = instance.sessionAdapter.startsAtAdapter.decode(session.startsAt!!),
                        endsAt = instance.sessionAdapter.endsAtAdapter.decode(session.endsAt!!),
                        serviceSession = if (session.isServiceSession) {
                            1
                        } else {
                            0
                        },
                        roomId = session.roomId!!.toLong(),
                        rsvp = dbSession.rsvp,
                        id = session.id
                )
            }

            var displayOrder = 0L
            for (sessionSpeaker in session.speakers) {
                instance.sessionSpeakerQueries.insertUpdate(
                        session.id,
                        sessionSpeaker.id,
                        displayOrder++)
            }
        }

        allSessions.forEach {
            if (!newIdSet.contains(it.id)) {
                instance.sessionQueries.deleteById(it.id)
            }
        }
    }
}
