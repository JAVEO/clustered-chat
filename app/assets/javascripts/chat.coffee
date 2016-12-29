msgform = -> $("#msgform")
createTopicForm = -> $("#topicform")
comment = -> $("#comment")
topicNameEl = -> $("#topicName")
currentTopicEl = -> $("#current-topic")
confirmButton = -> $("#sendMessageButton")
createTopicButton = -> $("#createTopicButton")
subscribeButton = -> $(".subscribe")
conversation = -> $("#conversation #messages")
messages = -> $("#messages")
topics = -> $("#topics")
messagesPager = -> $("#messages-pager")
topicsPanel = -> $("#topics-panel .topics-panel")
messageOnLeftTemplate = -> $("#message-on-left-template")
topicsOnLeftTemplate = -> $("#topics-on-left-template")

messageExists = ->
  comment().val().trim().length

topicNameEntered = ->
  topicNameEl().val().trim().length

changeConfirmButtonState = (disable) ->
  confirmButton().prop "disabled", disable

enableConfirmButton = ->
  changeConfirmButtonState(false)

disableConfirmButton = ->
  changeConfirmButtonState(true)

resetForm = ->
  comment().val("")

resetTopicForm = ->
  topicNameEl().val("")

getDateToPager = (direction, msgs) ->
  switch direction
    when "older"
      if !msgs.length
        return "" + new Date().getTime()
      return msgs[0].creationDate["$date"]
    when "newer"
      if !msgs.length
        throw "cannot go to newer messages when there is no messages on page"
      [..., last] = msgs
      return last.creationDate["$date"]
    else
      throw "wrong pager direction (should be 'older' or 'newer', was '" + direction + "')"

niceScrolls = ->
  conversation().niceScroll
    background: "#eee"
    cursorcolor: "#ddd"
    cursorwidth: "10px"
    autohidemode: false
    horizrailenabled: false
  topics().niceScroll
    background: "#eee"
    cursorcolor: "#ddd"
    cursorwidth: "10px"
    autohidemode: false
    horizrailenabled: false

init = ->
  disableConfirmButton()
  niceScrolls()

topicNames = {}
currentTopic = undefined

$ ->
  init()

  templateScript =
    messageOnLeft: messageOnLeftTemplate().html(),
    topicsOnLeft: topicsOnLeftTemplate().html()

  template =
    messageOnLeft: Handlebars.compile(templateScript.messageOnLeft),
    topicsOnLeft: Handlebars.compile(templateScript.topicsOnLeft)

  currentMessages = []

  ws = new WebSocket $("body").data("ws-url")
  ws.onmessage = (event) ->
    message = JSON.parse event.data
    switch message.type
      when "messages"
        messages().html("")
        message.messages.forEach (msg) ->
          messages().append(messageOnLeft(msg.user, msg.text))
        messages().scrollTop(messages().prop("scrollHeight"))
        currentMessages = message.messages
        console.log(message)
      when "message"
        messages().append(messageOnLeft(message.user, message.text))
        messages().scrollTop(messages().prop("scrollHeight"))
        currentMessages.push(message)
      when "topicName"
        topicNames[message.topicId] = message.topicName
        topics().append(topicsOnLeft(message.topicName, message.topicId))
        topics().scrollTop(topics().prop("scrollHeight"))
      when "topics"
        message.topics.forEach (topic) ->
          topicNames[topic.id] = topic.name
          topics().append(topicsOnLeft(topic.name, topic.id))
        topics().scrollTop(topics().prop("scrollHeight"))
      when "messages pager timeout"
        messages().html("service took too long to respond")
      else
        console.log(message)

  ws.onerror = (event) ->
    console.log "WS error: " + event

  ws.onclose = (event) ->
    console.log "WS closed: " + event.code + ": " + event.reason + " " + event

  window.onbeforeunload = ->
    ws.onclose = ->
    ws.close()

  msgform().submit (event) ->
  	if !currentTopic
  	  alert "You're not subscribed to any topic."
  	  return
    event.preventDefault()
    message = { type: "message", topic: currentTopic, msg: comment().val() }
    if messageExists()
      ws.send(JSON.stringify(message))
      resetForm()
      disableConfirmButton()

  createTopicForm().submit (event) ->
    event.preventDefault()
    message = topicNameEl().val()
    if topicNameEntered()
      ws.send(JSON.stringify(message))
      resetTopicForm()

  messageOnLeft = (u, m) ->
    template.messageOnLeft(messageInfo(u, m))

  topicsOnLeft = (topicName, topicId) ->
    template.topicsOnLeft(topicInfo(topicName, topicId))

  messageInfo = (user, message) ->
    user : user,
    message : message

  topicInfo = (topic, topicId) ->
    topicName: topic,
    topicId: topicId

  topics().on 'click', '.subscribe', (event) ->
    el = $(event.target)
    topicId = el.data("topic-id")
    topicName = topicNames[topicId]
    currentTopic = topicName
    message = {type: "subscribe", topic: topicName}
    ws.send(JSON.stringify(message))
    comment().prop "disabled", false
    enableConfirmButton()
    oldActive = topics().find(".subscribe.active")
    if oldActive
      oldActive.removeClass("active")
      oldActive.removeClass("label-info")
      oldActive.addClass("label-default")
      oldActive.prop "disabled", false
      oldActive.html "subscribe"
    el.addClass("active")
    el.removeClass("label-default")
    el.addClass("label-info")
    el.prop "disabled", true
    el.html "active"
    currentTopicEl().html currentTopic
    clearChat()

  clearChat = ->
    messages().html("")

  key_enter = 13

  comment().keyup (event) ->
    if currentTopic && messageExists()
      enableConfirmButton()
    else
      disableConfirmButton()
    if event.which is key_enter && !event.shiftKey
      event.preventDefault()
      if messageExists()
        msgform().submit()

  messagesPager().on 'click', '.pager-link', (event) ->
  	if !currentTopic
  	  alert "You're not subscribed to any topic."
  	  return
    el = $(event.target)
    direction = el.data("direction")
    date = getDateToPager(direction, currentMessages)
    message = { "type": "pager", "topic": currentTopic, "direction": direction, "date": date }
    ws.send(JSON.stringify(message))
