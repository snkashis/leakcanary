package leakcanary.internal.activity.screen

import android.app.AlertDialog
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.squareup.leakcanary.core.R
import leakcanary.GraphField
import leakcanary.GraphHeapValue
import leakcanary.GraphObjectRecord.GraphClassRecord
import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.GraphObjectRecord.GraphObjectArrayRecord
import leakcanary.GraphObjectRecord.GraphPrimitiveArrayRecord
import leakcanary.HeapValue.BooleanValue
import leakcanary.HeapValue.ByteValue
import leakcanary.HeapValue.CharValue
import leakcanary.HeapValue.DoubleValue
import leakcanary.HeapValue.FloatValue
import leakcanary.HeapValue.IntValue
import leakcanary.HeapValue.LongValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HeapValue.ShortValue
import leakcanary.HprofGraph
import leakcanary.HprofParser
import leakcanary.HprofParser.RecordCallbacks
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.internal.activity.db.Io
import leakcanary.internal.activity.db.Io.OnIo
import leakcanary.internal.activity.db.executeOnIo
import leakcanary.internal.activity.ui.SimpleListAdapter
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.inflate
import java.io.File

internal class HprofExplorerScreen(
  private val heapDumpFile: File
) : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_hprof_explorer).apply {
      container.activity.title = resources.getString(R.string.leak_canary_loading_title)

      lateinit var parser: HprofParser

      addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
        }

        override fun onViewDetachedFromWindow(view: View) {
          Io.execute {
            parser.close()
          }
        }
      })

      executeOnIo {
        container.activity.title =
          resources.getString(R.string.leak_canary_options_menu_explore_heap_dump)
        parser = HprofParser.open(heapDumpFile)
        val graph = HprofGraph(parser)
        val classInstances = mutableMapOf<Long, MutableList<Long>>()
        parser.scan(RecordCallbacks().on(InstanceDumpRecord::class.java) { record ->
          val instances = classInstances.getOrPut(record.classId, { mutableListOf() })
          instances += record.id
        })
        updateUi {
          val titleView = findViewById<TextView>(R.id.leak_canary_explorer_title)
          val searchView = findViewById<View>(R.id.leak_canary_search_button)
          val listView = findViewById<ListView>(R.id.leak_canary_explorer_list)
          titleView.visibility = VISIBLE
          searchView.visibility = VISIBLE
          listView.visibility = VISIBLE
          searchView.setOnClickListener {
            val input = EditText(context)
            AlertDialog.Builder(context)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("Type a fully qualified class name")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                  executeOnIo {
                    val partialClassName = input.text.toString()
                    val matchingClasses = classInstances.keys.filter {
                      graph.className(it)
                          .contains(partialClassName)
                    }.map { graph.readGraphObjectRecord(it).asClass!! }

                    if (matchingClasses.isEmpty()) {
                      updateUi {
                        Toast.makeText(
                            context, "No class matching [$partialClassName]", Toast.LENGTH_LONG
                        )
                            .show()
                      }
                    } else {
                      updateUi {
                        titleView.text = "${matchingClasses.size} instances matching [$partialClassName]"
                        listView.adapter = SimpleListAdapter(
                            R.layout.leak_canary_leak_row, matchingClasses
                        ) { view, position ->
                          val titleView = view.findViewById<TextView>(R.id.leak_canary_row_text)
                          titleView.text = matchingClasses[position].name
                        }
                        listView.setOnItemClickListener { _, _, position, _ ->
                          val selectedClass = matchingClasses[position]
                          executeOnIo {
                            val instances = classInstances[selectedClass.record.id]!!
                            updateUi {
                              titleView.text = "${instances.size} instances of class ${selectedClass.name}"
                              listView.adapter = SimpleListAdapter(
                                  R.layout.leak_canary_leak_row, instances
                              ) { view, position ->
                                val titleView = view.findViewById<TextView>(R.id.leak_canary_row_text)
                                titleView.text = "@${instances[position]}"
                              }
                              listView.setOnItemClickListener { _, _, position, _ ->
                                executeOnIo {
                                  val objectId = instances[position]
                                  val instance = graph.readGraphObjectRecord(objectId).asInstance!!
                                  val fields = fieldsForRendering(instance)
                                  showInstanceFields(titleView, objectId, selectedClass.name, listView, fields)
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
          }
        }
      }
    }

  private fun OnIo.showInstanceFields(
    titleView: TextView,
    objectId: Long,
    className: String,
    listView: ListView,
    fields: List<Pair<GraphField, String>>
  ) {
    updateUi {
      titleView.text = "@$objectId instance of class $className"
      listView.adapter = SimpleListAdapter(
          R.layout.leak_canary_leak_row, fields
      ) { view, position ->
        val titleView =
          view.findViewById<TextView>(R.id.leak_canary_row_text)
        titleView.text = fields[position].second
      }
      listView.setOnItemClickListener { _, _, position, _ ->
        val field = fields[position].first
        if (field.value.isNonNullReference) {
          executeOnIo {
            val instance = field.value.readObjectRecord()!!.asInstance
            if (instance != null) {
              val fields = fieldsForRendering(instance)
              showInstanceFields(titleView, instance.record.id, instance.className, listView, fields)
            }
          }
        }
      }
    }
  }

  private fun fieldsForRendering(instance: GraphInstanceRecord): List<Pair<GraphField, String>> {
    return instance.readFields()
        .map { field ->
          field to "${field.classRecord.simpleName}.${field.name}=${heapValueAsString(
              field.value
          )}"
        }
        .toList()
  }

  private fun heapValueAsString(heapValue: GraphHeapValue): String {
    return when (val actualValue = heapValue.actual) {
      is ObjectReference -> {
        if (heapValue.isNullReference) {
          "null"
        } else {
          when(val objectRecord = heapValue.readObjectRecord()!!) {
            is GraphInstanceRecord -> {
                if (objectRecord instanceOf "java.lang.String") {
                  "\"${objectRecord.readAsJavaString()!!}\""
                } else {
                  heapValue.readObjectRecord()!!.asInstance!!.className + "@${actualValue.value}"
                }
            }
            is GraphClassRecord -> {
              objectRecord.name
            }
            is GraphObjectArrayRecord -> {
              objectRecord.arrayClassName
            }
            is GraphPrimitiveArrayRecord -> {
              // TODO actual type
              "primitive array"
            }
          }
        }
      }
      is BooleanValue -> actualValue.value.toString()
      is CharValue -> actualValue.value.toString()
      is FloatValue -> actualValue.value.toString()
      is DoubleValue -> actualValue.value.toString()
      is ByteValue -> actualValue.value.toString()
      is ShortValue -> actualValue.value.toString()
      is IntValue -> actualValue.value.toString()
      is LongValue -> actualValue.value.toString()
    }

  }
}
