package changestream

import changestream.helpers.App

class ChangeStreamISpec extends App {
  "when handling an INSERT statement" should {
    "affecting a single row, generates a single insert event" in {
      queryAndWait(INSERT)

      assertValidEvent("insert", sql = Some(INSERT), isLastMutation = true)
    }
  }

  "when handling an INSERT statement" should {
    "affecting multiple rows, generates multiple insert events" in {
      queryAndWait(INSERT_MULTI)

      assertValidEvent("insert", queryRowCount = 2, currentRow = 1, transactionRowCount = 2, sql = Some(INSERT_MULTI))
      assertValidEvent("insert", queryRowCount = 2, currentRow = 2, transactionRowCount = 2, sql = Some(INSERT_MULTI), isLastMutation = true)
    }
  }

  "when handling an UPDATE statement" should {
    "affecting a single row, generates a single update event" in {
      queryAndWait(INSERT)
      waitAndClear()

      queryAndWait(UPDATE)
      assertValidEvent("update", sql = Some(UPDATE), isLastMutation = true)
    }
  }

  "when handling an UPDATE statement" should {
    "affecting multiple rows" in {
      queryAndWait(INSERT_MULTI)
      waitAndClear(2)

      queryAndWait(UPDATE_ALL)
      assertValidEvent("update", queryRowCount = 2, currentRow = 1, transactionRowCount = 2, sql = Some(UPDATE_ALL))
      assertValidEvent("update", queryRowCount = 2, currentRow = 2, transactionRowCount = 2, sql = Some(UPDATE_ALL), isLastMutation = true)
    }
  }

  "when handling an DELETE statement" should {
    "affecting a single row, generates a single delete event" in {
      queryAndWait(INSERT)
      waitAndClear()

      queryAndWait(DELETE)
      assertValidEvent("delete", sql = Some(DELETE), isLastMutation = true)
    }
    "affecting multiple rows" in {
      queryAndWait(INSERT)
      queryAndWait(INSERT)
      waitAndClear(2)

      queryAndWait(DELETE_ALL)
      assertValidEvent("delete", queryRowCount = 2, currentRow = 1, transactionRowCount = 2, sql = Some(DELETE_ALL))
      assertValidEvent("delete", queryRowCount = 2, currentRow = 2, transactionRowCount = 2, sql = Some(DELETE_ALL), isLastMutation = true)
    }
  }

  "when doing things in a transaction" should {
    "a successfully committed transaction" should {
      "buffers one change event to be able to properly tag the last event ina  transaction" in {
        queryAndWait("begin")
        queryAndWait(INSERT)
        queryAndWait(INSERT)
        queryAndWait(UPDATE_ALL)
        queryAndWait(DELETE_ALL)
        validateNoEvents

        queryAndWait("commit")
        assertValidEvent("insert", queryRowCount = 1, transactionRowCount = 6, currentRow = 1, sql = Some(INSERT))
        assertValidEvent("insert", queryRowCount = 1, transactionRowCount = 6, currentRow = 1, sql = Some(INSERT))
        assertValidEvent("update", queryRowCount = 2, transactionRowCount = 6, currentRow = 1, sql = Some(UPDATE_ALL))
        assertValidEvent("update", queryRowCount = 2, transactionRowCount = 6, currentRow = 2, sql = Some(UPDATE_ALL))
        assertValidEvent("delete", queryRowCount = 2, transactionRowCount = 6, currentRow = 1, sql = Some(DELETE_ALL))
        assertValidEvent("delete", queryRowCount = 2, transactionRowCount = 6, currentRow = 2, sql = Some(DELETE_ALL), isLastMutation = true)
      }
    }

    "a rolled back transaction" should {
      "generates no change events" in {
        queryAndWait("begin")
        queryAndWait(INSERT)
        queryAndWait(INSERT)
        queryAndWait(UPDATE_ALL)
        queryAndWait(DELETE_ALL)
        queryAndWait("rollback")
        validateNoEvents
      }
    }
  }
}

