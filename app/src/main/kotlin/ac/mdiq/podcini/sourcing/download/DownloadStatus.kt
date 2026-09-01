package ac.mdiq.podcini.sourcing.download

class DownloadStatus(
         val state: Int,
         val progress: Int) {

    enum class State(val code: Int) {
        UNKNOWN(0),
        QUEUED(1),
        RUNNING(2),
        COMPLETED(3),     // Both successful and not successful
        INCOMPLETE(4);
    }
}
