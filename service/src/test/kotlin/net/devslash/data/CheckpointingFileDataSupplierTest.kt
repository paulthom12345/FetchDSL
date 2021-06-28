package net.devslash.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import net.devslash.*
import net.devslash.outputs.LogResponse
import net.devslash.util.basicRequest
import net.devslash.util.basicResponse
import net.devslash.util.basicUrl
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI

@ExperimentalCoroutinesApi
internal class CheckpointingFileDataSupplierTest {

  @Rule
  @JvmField
  val tempFolder = TemporaryFolder()

  @Before
  fun setup() {
    val file = tempFolder.newFile("test.log")
    listOf("A", "B", "C").forEach {
      file.appendText(it + "\n")
    }
  }

  @Test
  fun testSingleFailureSingleError() = runBlockingTest {
    val root = tempFolder.root
    val fileName = root.resolve("test.log").absolutePath
    val supplier = CheckpointingFileDataSupplier(
      fileName,
      root.resolve("check.log").absolutePath
    )
    val rd = supplier.getDataForRequest()!!
    assertThat(rd.mustGet<List<String>>(), equalTo(listOf("A")))

    // Return success
    supplier.accept(basicRequest(), basicResponse(), rd)

    supplier.getDataForRequest()!!
    // At this point simulate a failure. The checkpoint should have B, C
    supplier.close()
    assertThat(
      root.resolve("check.log").readLines(),
      equalTo(listOf("B", "C"))
    )
  }

  @Test
  fun testInjectSetsRequired() {
    val root = tempFolder.root
    val fileName = root.resolve("test.log").absolutePath
    val supplier = CheckpointingFileDataSupplier(
      fileName,
      root.resolve("check.log").absolutePath
    )

    val call: Call<List<String>>
    supplier.use {
      call = CallBuilder<List<String>>("http://example.com").apply {
        inject(supplier)
        after {
          // Test append, not overwrite
          +LogResponse()
        }
      }.build()
    }

    assertThat(call.onError, equalTo(supplier))
    assertThat(call.dataSupplier, equalTo(supplier))
    assertThat(call.afterHooks, hasItem(supplier))
  }

  @Test
  fun testPredicateForSuccessFiltersCorrectly() = runBlockingTest {
    val root = tempFolder.root
    val fileName = root.resolve("test.log").absolutePath
    val supplier = CheckpointingFileDataSupplier(
      fileName,
      root.resolve("check.log").absolutePath, " ",
    ) {
      resp.statusCode != 200
    }

    val rd = supplier.getDataForRequest()!!
    val rd2 = supplier.getDataForRequest()!!

    runHttp {
      call("T") {
        inject(supplier)
      }
    }

    // In this form, 200 is failure
    supplier.accept(basicRequest(), basicResponse(), rd)
    supplier.accept(basicRequest(), HttpResponse(URI(basicUrl), 404, mapOf(), ByteArray(0)), rd2)

    supplier.close()
    val lines = root.resolve("check.log").readLines()
    assertThat(lines, equalTo(listOf("A", "C")))
  }

}
